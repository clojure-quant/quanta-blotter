(ns quanta.blotter.oms.flow.trading-state
  "OMS-level shared trading-state flows: order/position change pipelines,
   working-order / open-position dict streams, and a shared trading snapshot."
  (:require
   [missionary.core :as m]
   [quanta.blotter.flow.sample :as sample]
   [quanta.blotter.util :as util]
   [quanta.blotter.oms.flow.fill :as fill]
   [quanta.blotter.oms.flow.open-positions :as op]
   [quanta.blotter.oms.flow.working-orders :as wo]))

(def ^:private default-snapshot-interval-ms 250)

(defn- snapshot
  [working-orders open-positions]
  {:working-orders working-orders
   :open-positions open-positions})

(defn- trading-snapshot-flow*
  [{:keys [working-order-dict-flow open-position-dict-flow]}
   {:keys [interval-ms] :or {interval-ms default-snapshot-interval-ms}}]
  (let [wo-cont (util/cont (wo/working-order-list-from-dict-flow working-order-dict-flow))
        op-cont (util/cont (op/open-position-list-from-dict-flow open-position-dict-flow))]
    (sample/sample-continuous-on-change interval-ms snapshot wo-cont op-cont)))

(defn- drain-task
  "Permanent subscriber that keeps shared dict reductions alive."
  [working-order-dict-flow open-position-dict-flow]
  (m/reduce (fn [_ _v] nil) nil
            (util/mix working-order-dict-flow open-position-dict-flow)))

(defn start-trading-state!
  "Build shared trading-state flows from `oms` combined-flow and start a
   drain task so state is retained while the OMS runs.

   Returns a map with shared flows and :dispose!."
  [oms & {:keys [position-method snapshot-interval-ms]
          :or {position-method :fifo
               snapshot-interval-ms default-snapshot-interval-ms}}]
  (let [channel-flow (get-in oms [:consolidator :combined-flow])
        _ (assert channel-flow "start-trading-state! needs combined-flow")
        order-change-flow (m/stream (wo/order-change-flow channel-flow))
        fill-flow (m/stream (fill/fill-flow channel-flow))
        position-change-flow (m/stream (op/position-change-flow fill-flow {:method position-method}))
        working-order-dict-flow (m/stream (wo/working-order-dict-flow order-change-flow))
        open-position-dict-flow (m/stream (op/open-position-dict-flow position-change-flow))
        state {:order-change-flow order-change-flow
               :fill-flow fill-flow
               :position-change-flow position-change-flow
               :working-order-dict-flow working-order-dict-flow
               :open-position-dict-flow open-position-dict-flow
               :position-method position-method}
        trading-snapshot-flow (m/stream (trading-snapshot-flow* state {:interval-ms snapshot-interval-ms}))
        dispose-drain! ((drain-task working-order-dict-flow open-position-dict-flow)
                        #(println "trading-state drain done" %)
                        #(println "trading-state drain error" %))]
    (assoc state
           :trading-snapshot-flow trading-snapshot-flow
           :dispose! dispose-drain!)))

(defn stop-trading-state!
  [{:keys [dispose!]}]
  (when dispose! (dispose!)))
