(ns quanta.stresstest.tests.limit-buy-sell
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :as oms]
   [quanta.stresstest.near-market-limit-order :as near-market]
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

(defn- order-completed?
  "True once `order-id` is no longer in the working-order dict (filled/closed)."
  [order-id]
  (fn [state]
    (not (contains? (:working-orders state) order-id))))

(defn limit-buy-sell
  "Opens an aggressive near-market buy limit, waits for full fill and order
   completion, then closes with an aggressive near-market sell limit and waits
   for full fill and completion.

   `:offset-prct` should be negative so both legs price through the market."
  [{:keys [oms campaign] :as this} {:keys [account/id qty]
                                    :as order}]
  (m/sp
   (let [buy-order (m/? (near-market/near-market-limit-order oms (assoc order :side :buy)))
         open-message (m/? (oms/create-order oms (assoc buy-order
                                                        :campaign campaign
                                                        :account/id id)))
         open-id (:order-id open-message)
         _ (m/? (wait-for-state this (filled? open-id qty) :open-filled))
         _ (m/? (wait-for-state this (order-completed? open-id) :open-completed))
         sell-order (m/? (near-market/near-market-limit-order oms (assoc order :side :sell)))
         close-message (m/? (oms/create-order oms (assoc sell-order
                                                         :campaign campaign
                                                         :account/id id)))
         close-id (:order-id close-message)
         _ (m/? (wait-for-state this (filled? close-id qty) :close-filled))]
     (m/? (wait-for-state this (order-completed? close-id) :close-completed)))))
