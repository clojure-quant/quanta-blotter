(ns quanta.blotter.oms.portfolio.working-order
  (:require
   [quanta.blotter.precision :as precision]
   [tick.core :as t]))

(def closed-statuses
  "Order statuses that mean the order is no longer open."
  #{:cancelled :rejected :expired :filled})

(defn order-done? [order]
  (contains? closed-statuses (:order/status order)))

(defn initial-acc []
  {:history []})

(defn- conj-history [acc msg]
  (update acc :history conj msg))

(defn- stamp-order-date [acc msg]
  (if (and (nil? (:order-date acc)) (:date msg))
    (assoc acc :order-date (t/inst (:date msg)))
    acc))

(defn- apply-fill [acc {:keys [qty price position-id]}]
  (let [fill-qty (or (:fill-qty acc) 0M)
        fill-notional (or (:fill-notional acc) 0M)
        q (if qty (bigdec qty) 0M)
        p (if price (bigdec price) 0M)
        new-fill-qty (+ fill-qty q)
        new-notional (+ fill-notional (* q p))
        order-qty (:qty acc)
        filled? (and order-qty (>= new-fill-qty order-qty))
        price-scale (max (or (:price-scale acc) 0)
                         (.scale ^BigDecimal p))]
    (cond-> (assoc acc
                   :fill-qty new-fill-qty
                   :fill-notional new-notional
                   :price-scale price-scale)
      position-id (assoc :position-id position-id)
      filled? (assoc :terminal? true :terminal-status :filled))))

(defn- apply-modify [acc {:keys [qty limit]}]
  (cond-> acc
    qty (assoc :qty qty)
    limit (assoc :limit limit)))

(defn- init-from-new-order [acc msg]
  (assoc acc
         :order-id (:order-id msg)
         :account (:account/id msg)
         :asset (:asset msg)
         :side (:side msg)
         :qty (some-> (:qty msg) bigdec)
         :limit (some-> (:limit msg) bigdec)
         :order-type (:order-type msg)
         :fill-qty 0M
         :fill-notional 0M
         :price-scale 0
         :terminal? false
         :terminal-status nil
         :reject-text nil
         :campaign (:campaign msg)
         :label (:label msg)
         :position-id (:position-id msg)))

(defn- mark-terminal [acc status & {:keys [text]}]
  (cond-> (assoc acc :terminal? true :terminal-status status)
    text (assoc :reject-text text)))

(defn ready-to-emit? [acc]
  (some? (:qty acc)))

(defn to-order-view
  "Projects internal accumulator state to the public order map."
  [{:keys [order-id account asset side qty limit order-type fill-qty fill-notional
           price-scale history terminal? terminal-status reject-text order-date
           campaign label position-id]}]
  (let [qty-filled (or fill-qty 0M)
        scale (or price-scale 0)
        done? (true? terminal?)]
    (cond-> {:order/id order-id
             :order/account-id account
             :order/asset asset
             :order/side side
             :order/type order-type
             :order/status (if done? terminal-status :working)
             :order/qty qty
             :order/qty-filled qty-filled
             :order/qty-working (if done? 0M (- qty qty-filled))
             :order/avg-price (when (pos? qty-filled) (precision/div fill-notional qty-filled scale))
             :order/date (t/inst (or order-date (t/instant)))
             :order/history history}
      (and done? (= :rejected terminal-status) reject-text)
      (assoc :order/text (str reject-text))
      campaign (assoc :order/campaign campaign)
      label (assoc :order/label label)
      position-id (assoc :order/position-id position-id)
      limit (assoc :order/limit limit))))

(defn- public-order-view [acc]
  (dissoc (to-order-view acc) :order/history))

(defn view-changed? [prev-acc acc]
  (not= (public-order-view prev-acc)
        (public-order-view acc)))

(defn process-order-msg
  "Apply one channel message to a per-order accumulator."
  [acc msg]
  (let [acc (-> acc (conj-history msg) (stamp-order-date msg))]
    (case (:type msg)
      :trader/new-order
      (if (:qty acc)
        acc
        (init-from-new-order acc msg))

      :broker/order-modified
      (apply-modify acc msg)

      :broker/order-filled
      (if (:qty acc)
        (apply-fill acc msg)
        acc)

      :broker/order-canceled
      (if (:qty acc)
        (mark-terminal acc :cancelled)
        acc)

      :broker/order-rejected
      (if (:qty acc)
        (mark-terminal acc :rejected :text (:message msg))
        acc)

      :broker/order-expired
      (if (:qty acc)
        (mark-terminal acc :expired)
        acc)

      ;; no effect on order-state:
      :broker/order-confirmed acc
      :broker/cancel-confirmed acc
      :trader/cancel-order acc
      :broker/cancel-rejected acc
      :trader/modify-order acc
      :broker/modify-rejected acc
      acc)))

(defn step
  "Synchronous per-order step.
   Returns {:acc new-acc :order-change view-or-nil}."
  [acc msg]
  (let [prev acc
        acc (process-order-msg acc msg)
        emit? (and (ready-to-emit? acc)
                   (or (not (ready-to-emit? prev))
                       (view-changed? prev acc)))]
    {:acc acc
     :order-change (when emit? (to-order-view acc))}))

(defn update-working-order-dict
  "Apply an order-change view to the live working-order dictionary."
  [dict order]
  (let [k (:order/id order)]
    (if (order-done? order)
      (dissoc dict k)
      (assoc dict k order))))

(defn hydrate-acc-from-order
  "Build a per-order accumulator from a persisted order row (no history)."
  [order]
  (let [qty (:order/qty order)
        qty-filled (or (:order/qty-filled order) 0M)
        avg (:order/avg-price order)
        status (:order/status order)
        done? (contains? closed-statuses status)
        fill-notional (if (and avg (pos? qty-filled))
                        (* avg qty-filled)
                        0M)
        price-scale (if avg (.scale ^BigDecimal (bigdec avg)) 0)]
    (cond-> {:history []
             :order-id (:order/id order)
             :account (:order/account-id order)
             :asset (:order/asset order)
             :side (:order/side order)
             :qty (some-> qty bigdec)
             :limit (some-> (:order/limit order) bigdec)
             :order-type (:order/type order)
             :fill-qty (bigdec qty-filled)
             :fill-notional (bigdec fill-notional)
             :price-scale price-scale
             :terminal? done?
             :terminal-status (when done? status)
             :reject-text (:order/text order)
             :order-date (:order/date order)
             :campaign (:order/campaign order)
             :label (:order/label order)
             :position-id (:order/position-id order)}
      true identity)))
