(ns quanta.blotter.oms.portfolio.working-order
  (:require
   [quanta.blotter.precision :as precision]))

(def closed-statuses
  "Order statuses that mean the order is no longer open."
  #{:cancelled :rejected :expired :filled})

(defn order-done? [order]
  (contains? closed-statuses (:order/status order)))

(defn- conj-history [order msg]
  (update order :order/history (fnil conj []) msg))


(defn- recompute-working [order]
  (let [qty (:order/qty order)
        qty-filled (or (:order/qty-filled order) 0M)
        done? (order-done? order)]
    (assoc order
           :order/qty-working (if (or done? (nil? qty))
                                0M
                                (- qty qty-filled)))))

(defn- recompute-avg-price [order last-fill]
  (let [qty-filled (or (:order/qty-filled order) 0M)
        current-avg (some-> (:order/avg-price order) bigdec)
        fill-notional (if current-avg
                        (* qty-filled current-avg)
                        0M)
        last-fill-qty (bigdec (or (:qty last-fill) 0M))
        last-fill-price (some-> (:price last-fill) bigdec)
        new-qty-filled (+ qty-filled last-fill-qty)
        new-notional (+ fill-notional
                        (* last-fill-qty (or last-fill-price 0M)))
        scale (max (if current-avg (.scale ^BigDecimal current-avg) 0)
                   (if last-fill-price (.scale ^BigDecimal last-fill-price) 0))]
    (assoc order
           :order/avg-price (when (pos? new-qty-filled)
                              (precision/div new-notional new-qty-filled scale)))))

(defn- apply-fill [order {:keys [qty position-id] :as last-fill}]
  (let [fill-qty (or (:order/qty-filled order) 0M)
        q (if qty (bigdec qty) 0M)
        new-fill-qty (+ fill-qty q)
        order-qty (:order/qty order)
        filled? (and order-qty (>= new-fill-qty order-qty))
        order (recompute-avg-price order last-fill)]
    (-> order
        (assoc :order/qty-filled new-fill-qty)
        (cond-> position-id (assoc :order/position-id position-id)
                filled? (assoc :order/status :filled))
        recompute-working)))

(defn- apply-modify [order {:keys [qty limit]}]
  (-> order
      (cond-> qty (assoc :order/qty (bigdec qty))
              limit (assoc :order/limit (bigdec limit)))
      recompute-working))

(defn init-from-new-order
  "Build a DB-shaped working-order entry from a :trader/new-order message."
  [{:keys [date
           qty order-id account/id asset side order-type
           ; optional keys
           campaign label position-id limit] :as msg}]
  (cond-> {:order/date date
           :order/id order-id
           :order/account-id id
           :order/asset asset
           :order/side side
           :order/type order-type
           :order/status :working
           :order/qty qty
           :order/qty-filled 0M
           :order/qty-working qty
           :order/avg-price nil
           :order/history [msg]}
    campaign (assoc :order/campaign campaign)
    label  (assoc :order/label label)
    position-id (assoc :order/position-id position-id)
    limit (assoc :order/limit limit)))

(defn- mark-terminal [order status & {:keys [text]}]
  (-> order
      (assoc :order/status status)
      (cond-> text (assoc :order/text (str text)))
      recompute-working))


(defn process-orderupdate-msg
  "Apply one channel message to a DB-shaped working-order entry."
  [order msg]
  (let [order (conj-history order msg)]
    (case (:type msg)

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
      ; else, no effect on order-state
      order)))



(defn process-message [working-order {:keys [order-id] :as msg}]
  (let [known-order (get working-order order-id)]
    (if (= :trader/new-order (:type msg))
      ; new order
      (if known-order
        ; duplicate trader/new-order
        {:update-for-unknown msg}
        ; valid new order
        (let [order (init-from-new-order msg)]
          {:order-change order
           :order-opened order
           :working-order (assoc working-order order-id order)}))
      ; update order
      (if known-order
        ; valid order-update
        (let [order (process-orderupdate-msg known-order msg)
              done? (order-done? order)]
          (if done?
            {:order-closed order
             :order-change order
             :working-order (dissoc working-order order-id)}
            {:order-change order
             :working-order (assoc working-order order-id order)}))
        ; order-update unknown order 
        {:update-for-unknown msg}))))

(defn process-order-orderupdate-message [working-orders {:keys [type] :as msg}]
  (if (contains? #{:trader/new-order :trader/cancel-order :trader/modify-order
                   :broker/order-confirmed :broker/order-rejected
                   :broker/cancel-confirmed :broker/cancel-rejected :broker/order-canceled
                   :broker/order-modified :broker/modify-rejected
                   :broker/order-filled
                   :broker/order-expired} type)
    (process-message working-orders msg)
    {}))



(defn hydrate-order
  "Build a DB-shaped working-order entry from a persisted order row."
  [order]
  (let [qty (some-> (:order/qty order) bigdec)
        qty-filled (bigdec (or (:order/qty-filled order) 0M))
        history (let [h (:order/history order)]
                  (if (sequential? h) (vec h) []))]
    (-> order
        (dissoc :db/id :order/account-db)
        (assoc :order/qty qty
               :order/qty-filled qty-filled
               :order/history history)
        (cond-> (some? (:order/limit order))
          (assoc :order/limit (bigdec (:order/limit order))))
        recompute-working)))
