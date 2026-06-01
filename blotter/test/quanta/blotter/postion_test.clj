(ns quanta.blotter.postion-test
  (:require
   [clojure.test :refer :all]
   [missionary.core :as m]
   [quanta.blotter.order-manager.position :refer [position-change-flow
                                         open-positions-flow]]))

(def trade-flow
  (m/seed [{:account :rene1
            :asset "BTC"
            :side :buy
            :qty 0.001}
           {:account :rene1
            :asset "BTC"
            :side :buy
            :qty 0.002}
           {:account :rene1
            :asset "BTC"
            :side :buy
            :qty 0.003} ; 3 buys = 0.006
           {:account :rene1
            :asset "ETH"
            :side :buy
            :qty 0.007}
           {:account :rene1
            :asset "ETH"
            :side :sell
            :qty 0.007} ; buy and sell = no position
           ]))

(def transaction-flow (position-change-flow trade-flow))

(def position-flow (open-positions-flow transaction-flow))

(def position-transactions
  (m/? (m/reduce conj transaction-flow)))

position-transactions
;; => [[[:rene1 "BTC"] :open]
;;     [[:rene1 "BTC"] 0.001]
;;     [[:rene1 "BTC"] 0.003]
;;     [[:rene1 "ETH"] :open] ; weird - BTC trade was first
;;     [[:rene1 "BTC"] 0.006] ; should be before ETH open.
;;     [[:rene1 "ETH"] 0.007]
;;     [[:rene1 "ETH"] :close]]

(def open-positions
  (m/? (m/reduce conj position-flow)))

open-positions
;; => [{[:rene1 "BTC"] 0.001}
;;     {[:rene1 "BTC"] 0.003}
;;     {[:rene1 "BTC"] 0.006}
;;     {[:rene1 "BTC"] 0.006, [:rene1 "ETH"] 0.007}
;;     {[:rene1 "BTC"] 0.006}]

(deftest position
  (testing "position-transactions"
    (is (= position-transactions
           [[[:rene1 "BTC"] :open]
            [[:rene1 "BTC"] 0.001]
            [[:rene1 "BTC"] 0.003]
            [[:rene1 "ETH"] :open] ; weird - BTC trade was first
            [[:rene1 "BTC"] 0.006] ; should be before ETH open.
            [[:rene1 "ETH"] 0.007]
            [[:rene1 "ETH"] :close]]))))


