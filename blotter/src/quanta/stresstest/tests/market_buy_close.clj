(ns quanta.stresstest.tests.market-buy-close
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :as oms]
   [quanta.stresstest.runner :refer [wait-for-state]]))

(defn- filled?
  "True once campaign fills for `order-id` sum to at least `qty`."
  [order-id qty]
  (fn [state]
    (let [filled-qty (->> (:fills state)
                          (filter #(= order-id (:fill/order-id %)))
                          (map :fill/qty)
                          (reduce + 0M))]
      (>= filled-qty qty))))

(defn market-buy-close
  "Market buy to open, wait for full fill, market sell to close, wait for full fill."
  [{:keys [oms campaign] :as this} {:keys [account/id asset qty]
                                    :as order}]
  (m/sp
   (let [open-message (m/? (oms/create-order oms (assoc order
                                                        :campaign campaign
                                                        :account/id id
                                                        :side :buy
                                                        :order-type :market
                                                        :qty qty)))
         open-id (:order-id open-message)
         _ (m/? (wait-for-state this (filled? open-id qty) :open-filled))
         close-message (m/? (oms/create-order oms (assoc order
                                                         :campaign campaign
                                                         :account/id id
                                                         :side :sell
                                                         :order-type :market
                                                         :qty qty)))
         close-id (:order-id close-message)]
     (m/? (wait-for-state this (filled? close-id qty) :close-filled))
     (m/? (m/sleep 250)) ; make sure working order and fill is in sync.
     )))
