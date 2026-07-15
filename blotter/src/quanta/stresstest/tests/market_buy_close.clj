(ns quanta.stresstest.tests.market-buy-close
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :as oms]
   [quanta.stresstest.runner :refer [wait-for-state]]
   ))

(defn market-buy-close
  [{:keys [oms campaign] :as this} {:keys [account/id asset qty]
                                    :as order}]
  (m/sp
   (let [open-message (m/? (oms/create-order oms (assoc order 
                                                        :campaign campaign
                                                        :account/id id
                                                        :side :buy
                                                        :order-type :market
                                                        :qty qty
                                                        )))
         order-id (:order-id open-message)
         working? #(contains? (:working-orders %) order-id)
         _   (m/? (wait-for-state this working? :order-open))
         close-message (m/? (oms/create-order oms (assoc order
                                                         :campaign campaign
                                                         :account/id id
                                                         :side :sell
                                                         :order-type :market
                                                         :qty qty)))]
     
          (m/? (wait-for-state this (complement working?) :order-canceled)))))
