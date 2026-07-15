(ns quanta.stresstest.tests.limit-near-market-open-cancel
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :as oms]
   [quanta.stresstest.near-market-limit-order :as near-market]
   [quanta.stresstest.runner :refer [wait-for-state]]
   ))

(defn limit-near-market-open-cancel
  "Opens one near-market limit order, waits, and cancels it.

   The returned task completes only after the campaign has no working orders,
   open positions, or fills. `:open-ms` controls how long the order remains
   open and defaults to 1000. `:lifecycle-timeout-ms` defaults to 10000."
  [{:keys [oms campaign] :as this} {:keys [account/id asset qty side offset-prct]
                                    :as order}]
  (m/sp
   (let [limit-order (m/? (near-market/near-market-limit-order oms order))
         open-message (m/? (oms/create-order oms (assoc limit-order :campaign campaign
                                                                    :account/id id
                                                        )))
         order-id (:order-id open-message)
         working? #(contains? (:working-orders %) order-id)]
     (m/? (wait-for-state this working? :order-open))
     (m/? (oms/cancel-order oms {:account/id id
                                 :order-id order-id
                                 :asset asset}))
     (m/? (wait-for-state this (complement working?) :order-canceled)))))
