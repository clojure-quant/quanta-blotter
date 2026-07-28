(ns quanta.blotter.oms.portfolio.working-order
  (:require
   [quanta.blotter.precision :as precision]
   [tick.core :as t]))

(def closed-statuses
  "Order statuses that mean the order is no longer open."
  #{:cancelled :rejected :expired :filled})

(defn order-done? [order]
  (contains? closed-statuses (:order/status order)))

(defn- conj-history [order msg]
  (update order :order/history (fnil conj []) msg))

(defn- stamp-order-date [order msg]
  (if (and (nil? (:order/date order)) (:date msg))
    (assoc order :order/date (t/inst (:date msg)))
    order))

(defn- recompute-working [order]
  (let [qty (:order/qty order)
        qty-filled (or (:order/qty-filled order) 0M)
        done? (order-done? order)]
    (assoc order
           :order/qty-working (if (or done? (nil? qty))
                                0M
                                (- qty qty-filled)))))

(defn- recompute-avg-price [order]
  (let [qty-filled (or (:order/qty-filled order) 0M)
        fill-notional (or (:fill-notional order) 0M)
        scale (or (:price-scale order) 0)]
    (assoc order
           :order/avg-price (when (pos? qty-filled)
                              (precision/div fill-notional qty-filled scale)))))

(defn- apply-fill [order {:keys [qty price position-id]}]
  (let [fill-qty (or (:order/qty-filled order) 0M)
        fill-notional (or (:fill-notional order) 0M)
        q (if qty (bigdec qty) 0M)
        p (if price (bigdec price) 0M)
        new-fill-qty (+ fill-qty q)
        new-notional (+ fill-notional (* q p))
        order-qty (:order/qty order)
        filled? (and order-qty (>= new-fill-qty order-qty))
        price-scale (max (or (:price-scale order) 0)
                         (.scale ^BigDecimal p))]
    (-> order
        (assoc :order/qty-filled new-fill-qty
               :fill-notional new-notional
               :price-scale price-scale)
        (cond-> position-id (assoc :order/position-id position-id)
                filled? (assoc :order/status :filled))
        recompute-avg-price
        recompute-working)))

(defn- apply-modify [order {:keys [qty limit]}]
  (-> order
      (cond-> qty (assoc :order/qty (bigdec qty))
              limit (assoc :order/limit (bigdec limit)))
      recompute-working))

(defn init-from-new-order
  "Build a DB-shaped working-order entry from a :trader/new-order message."
  [msg]
  (let [qty (some-> (:qty msg) bigdec)]
    (cond-> {:order/id (:order-id msg)
             :order/account-id (:account/id msg)
             :order/asset (:asset msg)
             :order/side (:side msg)
             :order/type (:order-type msg)
             :order/status :working
             :order/qty qty
             :order/qty-filled 0M
             :order/qty-working (or qty 0M)
             :order/avg-price nil
             :order/date (when (:date msg) (t/inst (:date msg)))
             :order/history []
             :fill-notional 0M
             :price-scale 0}
      (:campaign msg) (assoc :order/campaign (:campaign msg))
      (:label msg) (assoc :order/label (:label msg))
      (:position-id msg) (assoc :order/position-id (:position-id msg))
      (:limit msg) (assoc :order/limit (bigdec (:limit msg))))))

(defn- mark-terminal [order status & {:keys [text]}]
  (-> order
      (assoc :order/status status)
      (cond-> text (assoc :order/text (str text)))
      recompute-working))

(defn ready-to-emit? [order]
  (some? (:order/qty order)))

(defn- public-order-view [order]
  (dissoc order :order/history))

(defn view-changed? [prev order]
  (not= (public-order-view prev)
        (public-order-view order)))

(defn process-order-msg
  "Apply one channel message to a DB-shaped working-order entry."
  [order msg]
  (let [order (-> (or order {}) (conj-history msg) (stamp-order-date msg))]
    (case (:type msg)
      :trader/new-order
      (if (:order/qty order)
        order
        (-> (init-from-new-order msg)
            (assoc :order/history (:order/history order))
            (cond-> (:order/date order) (assoc :order/date (:order/date order))
                    (and (nil? (:order/date order)) (:date msg))
                    (assoc :order/date (t/inst (:date msg))))))

      :broker/order-modified
      (apply-modify order msg)

      :broker/order-filled
      (if (:order/qty order)
        (apply-fill order msg)
        order)

      :broker/order-canceled
      (if (:order/qty order)
        (mark-terminal order :cancelled)
        order)

      :broker/order-rejected
      (if (:order/qty order)
        (mark-terminal order :rejected :text (:message msg))
        order)

      :broker/order-expired
      (if (:order/qty order)
        (mark-terminal order :expired)
        order)

      ;; no effect on order-state:
      :broker/order-confirmed order
      :broker/cancel-confirmed order
      :trader/cancel-order order
      :broker/cancel-rejected order
      :trader/modify-order order
      :broker/modify-rejected order
      order)))

(defn step
  "Synchronous per-order step.
   Returns {:order new-order :order-change order-or-nil}."
  [order msg]
  (let [prev (or order {})
        order (process-order-msg order msg)
        emit? (and (ready-to-emit? order)
                   (or (not (ready-to-emit? prev))
                       (view-changed? prev order)))
        ;; Emit a concrete date without persisting a fallback into live state,
        ;; so a later dated channel message can still stamp :order/date.
        order-change (when emit?
                       (cond-> order
                         (nil? (:order/date order))
                         (assoc :order/date (t/inst (t/instant)))))]
    {:order order
     :order-change order-change}))

(defn update-working-order-dict
  "Apply an order-change to the live working-order dictionary."
  [dict order]
  (let [k (:order/id order)]
    (if (order-done? order)
      (dissoc dict k)
      (assoc dict k order))))

(defn hydrate-order
  "Build a DB-shaped working-order entry from a persisted order row."
  [order]
  (let [qty (some-> (:order/qty order) bigdec)
        qty-filled (bigdec (or (:order/qty-filled order) 0M))
        avg (:order/avg-price order)
        fill-notional (if (and avg (pos? qty-filled))
                        (* (bigdec avg) qty-filled)
                        0M)
        price-scale (if avg (.scale ^BigDecimal (bigdec avg)) 0)
        history (let [h (:order/history order)]
                  (if (sequential? h) (vec h) []))]
    (-> order
        (dissoc :db/id :order/account-db)
        (assoc :order/qty qty
               :order/qty-filled qty-filled
               :order/history history
               :fill-notional (bigdec fill-notional)
               :price-scale price-scale)
        (cond-> (some? (:order/limit order))
          (assoc :order/limit (bigdec (:order/limit order))))
        recompute-working
        recompute-avg-price)))
