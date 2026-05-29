(ns quanta.market.trade.working-order
  (:require 
   [tick.core :as t]
   [quanta.market.trade.schema :as s]
   ))

(def update-types
  #{; open
    :order/new
    ; close
    :order/canceled
    :order/expired
    :order/cancelled
    ; trade
    :order/trade})

(def order-status
  #{; create new order
    :order/new
    :order/order-confirm
    :order/reject

    ; cancel order
    :order/cancel-req
    :order/cancel-reject
    :order/cancel-confirm
    :order/cancelled

    ; trade
    :order/fill-partial
    :order/fill

    ; expired orders
    :order/expired})

(defn create-working-order [order]
  {:order order
   :order-status {:status :open
                  :open-date (t/inst)
                  :fill-qty 0.0
                  :fill-value 0.0}
   
   }
 )

(defn close-order [order-status reason]
  (assoc order-status
         :status :closed
         :close-reason reason
         :close-date (t/inst)))

(defn process-trade [order order-status trade-qty trade-value] 
  (assert trade-qty "trade processor needs trade-qty")
  (assert trade-value "trade processor needs trade-value")
  (assert order "trade processor needs order")
  (let [new-order-status (-> order-status
                             (update :fill-qty + trade-qty)
                             (update :fill-value + trade-value))
        new-order-status (if (>= (:fill-qty new-order-status) (:qty order))
                           (close-order order-status "filled")
                           new-order-status)]
    {:new-trade {:side (:side order)
                 :asset (:asset order)
                 :timestamp (t/inst)
                 :qty trade-qty
                 :value trade-value}
     :new-order-status new-order-status}))

(defn process-close [order-status reason]
  {:new-order-status (close-order order-status reason)})

(defn open? [order-status]
  (= (:status order-status) :open))

(defn process-order-update [{:keys [order order-status] :as working-order}
                            {:keys [orderupdatetype trade-qty trade-value] :as order-update}]
   (println "processing order-status: " order-status "orderupdate: " order-update)
  (cond

    (not (s/validate-order-update order-update))
    {:alert {:text "order-update schema error"
             :error (s/human-error-order-update order-update)}}
    
    ; sets :new-trade and :new-order-status
    (= :trade orderupdatetype)
    (process-trade order order-status trade-qty trade-value)

    ; no action
    (= :comment orderupdatetype)
    {}

    ; close - sets :new-order-status
    (= :rejected orderupdatetype)
    (process-close order-status "rejected")
    (= :canceled orderupdatetype)
    (process-close order-status "canceled")
    (= :expired orderupdatetype)
    (process-close order-status "expired")

    ; unknown - alert
    :else
    {:alert {:text "unknown order-status"
             :order-update order-update
             :order-status order-status
             :order order}}))
 
