(ns demo.util.orderflow-simulated
  (:require
   [missionary.core :as m]
   [demo.util.time-flow :refer [create-time-flow]]))


(def demo-order-action-flow
  (create-time-flow
   [0 {:type :new-order
       :account/id 1
       :order-id 1
       :asset "BTCUSDT"
       :side :buy
       :limit 100.0
       :qty 0.001}
    2 {:type :new-order
       :account/id 2
       :order-id 2
       :asset "ETHUSDT"
       :side :sell
       :limit 100.0
       :qty 0.001}
    3 {:type :cancel-order
       :account/id 2
       :order-id 2}
    5 {:type :new-order
       :account/id 2
       :order-id 3
       :asset "ETHUSDT"
       :side :sell
       :limit 100.0
       :qty 0.001}
    7 {:type :new-order
       :account/id 2
       :order-id 4
       :asset "ETHUSDT"
       :side :sell
       :limit 100.0
       :qty 0.001}]))




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





