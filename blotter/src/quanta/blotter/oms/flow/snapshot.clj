(ns quanta.blotter.oms.flow.snapshot
  "Live trading snapshot: working orders + open positions, sampled on change."
  (:require
   [quanta.blotter.flow.sample :as sample]
   [quanta.blotter.util :as util]
   [quanta.blotter.oms.flow.fill :as fill]
   [quanta.blotter.oms.flow.open-positions :as op]
   [quanta.blotter.oms.flow.working-orders :as wo]))

(def ^:private default-interval-ms 250)

(defn snapshot
  [working-orders open-positions]
  {:working-orders working-orders
   :open-positions open-positions})

(defn trading-snapshot-flow
  "Return a discrete flow of `{:working-orders [...] :open-positions [...]} maps,
   sampled every `interval-ms` and emitted only when the snapshot changes."
  ([oms]
   (trading-snapshot-flow oms {}))
  ([oms {:keys [method interval-ms]
         :or {method :fifo interval-ms default-interval-ms}}]
   (let [channel-flow (get-in oms [:consolidator :combined-flow])
         _ (assert channel-flow "trading-snapshot-flow needs combined-flow")
         wo-list-f (wo/working-order-list-flow (wo/order-change-flow channel-flow))
         op-list-f (op/open-position-list-flow
                    (op/position-change-flow (fill/fill-flow channel-flow)
                                             {:method method}))
         wo-cont (util/cont wo-list-f)
         op-cont (util/cont op-list-f)]
     (sample/sample-continuous-on-change interval-ms snapshot wo-cont op-cont))))

(defn trading-snapshot-fn
  "Flowy :mode :ap entry point. `oms` is injected from server ctx."
  [oms]
  (trading-snapshot-flow oms))
