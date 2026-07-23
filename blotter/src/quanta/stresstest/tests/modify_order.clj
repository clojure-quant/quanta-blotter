(ns quanta.stresstest.tests.modify-order
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :as oms]
   [quanta.stresstest.near-market-limit-order :as near-market]
   [quanta.stresstest.runner :refer [wait-for-state]]))

(defn- order-modified?
  "True once working order `order-id` has the expected qty and limit."
  [order-id qty-modified limit-modified]
  (fn [state]
    (when-let [wo (get-in state [:working-orders order-id])]
      (and (== qty-modified (:order/qty wo))
           (== limit-modified (:order/limit wo))))))

(defn modify-order
  "Opens a resting buy limit/stop, modifies qty and limit, waits for the
   working-order update, then cancels.

   Expects no fills. `:modify-qty-prct` is percent of original qty (30 → 30%).
   `:modify-price-prct` increases the limit by that percent."
  [{:keys [oms campaign] :as this}
   {:keys [account/id asset qty order-type
           modify-price-prct modify-qty-prct]
    :or {order-type :limit}
    :as order}]
  (m/sp
   (let [placed (m/? (near-market/near-market-limit-order
                      oms (-> order
                              (assoc :side :buy :order-type order-type)
                              (dissoc :modify-price-prct :modify-qty-prct :expect))))
         open-message (m/? (oms/create-order oms (assoc placed
                                                        :campaign campaign
                                                        :account/id id)))
         order-id (:order-id open-message)
         initial-limit (:limit placed)
         qty-modified (* qty (/ (bigdec modify-qty-prct) 100M))
         limit-modified (* initial-limit (+ 1M (/ (bigdec modify-price-prct) 100M)))
         working? #(contains? (:working-orders %) order-id)]
     (m/? (wait-for-state this working? :order-open))
     (m/? (oms/modify-order oms {:account/id id
                                 :order-id order-id
                                 :asset asset
                                 :qty qty-modified
                                 :limit limit-modified}))
     (m/? (wait-for-state this
                          (order-modified? order-id qty-modified limit-modified)
                          :order-modified))
     (m/? (oms/cancel-order oms {:account/id id
                                 :order-id order-id
                                 :asset asset}))
     (m/? (wait-for-state this (complement working?) :order-canceled)))))
