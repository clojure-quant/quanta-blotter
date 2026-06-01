(ns demo.util.orderflow-simulated
  (:require
   [missionary.core :as m]
   [demo.util.time-flow :refer [create-time-flow]]))


(def demo-order-action-flow
  (create-time-flow
   [1 {:type :trader/new-order
       :account/id 1
       :order-id 1
       :asset "BTCUSDT"
       :side :buy
       :limit 1000.0M
       :qty 0.001M}
    2 {:type :trader/new-order
       :account/id 2
       :order-id 2
       :asset "ETHUSDT"
       :side :sell
       :limit 101.0M
       :qty 0.001M}
    3 {:type :trader/cancel-order
       :account/id 2
       :order-id 2}
    5 {:type :trader/new-order
       :account/id 2
       :order-id 3
       :asset "ETHUSDT"
       :side :sell
       :limit 99.3M
       :qty 0.001M}
    7 {:type :trader/new-order
       :account/id 2
       :order-id 4
       :asset "ETHUSDT"
       :side :sell
       :limit 98.7M
       :qty 0.001M}]))




(comment

  (m/? (m/reduce println nil demo-order-action-flow))

  ;; will print over time the following:
; nil {:type :new-order, :order-id 1, :asset :BTC, :side :buy, :limit 100.0, :qty 0.001}
; nil {:type :new-order, :order-id 2, :asset :ETH, :side :sell, :limit 100.0, :qty 0.001}
; nil {:type :cancel-order, :order-id 2}
; nil {:type :new-order, :order-id 3, :asset :ETH, :side :sell, :limit 100.0, :qty 0.001}
; nil {:type :new-order, :order-id 4, :asset :ETH, :side :sell, :limit 100.0, :qty 0.001}





; 
  )





