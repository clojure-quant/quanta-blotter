(ns quanta.blotter.oms.flow.snapshot
  "Live trading snapshot: working orders + open positions, sampled on change."
  (:require
   [quanta.blotter.oms.flow.trading-state :as trading-state]))

(defn snapshot
  [working-orders open-positions]
  {:working-orders working-orders
   :open-positions open-positions})

(defn trading-snapshot-flow
  "Return the shared OMS trading-snapshot flow (one per OMS, multicast to clients)."
  ([oms]
   (trading-snapshot-flow oms {}))
  ([oms _opts]
   (let [{:keys [trading-snapshot-flow]} (:trading-state oms)]
     (assert trading-snapshot-flow "trading-snapshot-flow needs :trading-state")
     trading-snapshot-flow)))

(defn trading-snapshot-fn
  "Flowy :mode :ap entry point. `oms` is injected from server ctx."
  [oms]
  (trading-snapshot-flow oms))
