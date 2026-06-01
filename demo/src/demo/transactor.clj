(ns demo.transactor
  (:require
   [missionary.core :as m]
   [quanta.blotter.order-manager.transactor :refer [create-transactor transactor-start transactor-log-start!]]))

(def order-orderupdate-flow
  (m/seed [{:order-id "456"
            :order {:order-id "456"
                    :account :demo4
                    :asset "BTC"
                    :side :buy
                    :limit 60000.0
                    :qty 0.14}}
           {:order-id "456"
            :broker-order-status {:order-id "456"
                                  :status :open
                                  :fill-qty 0.05
                                  :fill-value 200.0}}
           {:order-id "456"
            :broker-order-status {:order-id "456"

                                  :status :open
                                  :fill-qty 0.11
                                  :fill-value 500.0}}]))

(def tm (create-transactor {:order-orderupdate-flow order-orderupdate-flow}))

(transactor-log-start! tm "transactor2.txt")


(transactor-start tm)

