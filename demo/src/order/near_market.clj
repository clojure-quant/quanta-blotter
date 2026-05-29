(ns demo.order.near-market
  (:require
   [missionary.core :as m]
   [quanta.market.protocol :as p]
   [quanta.market.quote.core :refer [topic-snapshot]]
   [quanta.market.robot.order :refer [limit-near-market
                                      limit-order-near-market
                                      place-order-near-market]]
   [demo.env :refer [qm pm]]))

(m/? (topic-snapshot qm {:feed :bybit
                         :asset "BTCUSDT"
                         :topic :asset/trade}))
;; :timeout
;; => {:asset "BTCUSDT", :price 54651.94, :size 0.001636, :time 1723069620898, :BT false, :S "Sell", :i "2290000000301598745"}

(m/? (limit-near-market qm {:asset "BTCUSDT"
                            :side :buy
                            :feed :bybit
                            :diff 1.0}))

;; => 54596.62

(m/? (limit-order-near-market qm {:asset "BTCUSDT"
                                  :side :buy
                                  :account :rene/test4
                                  :qty 0.001
                                  :feed :bybit
                                  :diff 0.001}))
;; => {:side :buy, 
;;     :account :rene/test4, 
;;     :limit 55050.58, 
;;     :asset "BTCUSDT.S", 
;;     :qty 0.001, 
;;     :ordertype :limit}

(m/? (place-order-near-market {:qm qm :pm pm}
                              {:asset "BTCUSDT"
                               :side :buy
                               :account :rene/test4
                               :qty 0.001
                               :feed :bybit
                               :diff 0.1}))










