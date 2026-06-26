(ns quanta.blotter.oms.flow.trader
  (:require
   [missionary.core :as m]
   ))


(defn trader? [msg]
  (let [t (:type msg)]
    (or (= t :trader/new-order)
        (= t :trader/cancel-order)
        (= t :trader/modify-order))))
   


(defn trader-req-flow
  [channel-flow]
  (m/eduction 
      (filter trader?)
      channel-flow))