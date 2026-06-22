(ns quanta.blotter.oms.flow.print
  (:require
   [missionary.core :as m]
   [quanta.blotter.util :as util]
   [quanta.missionary.logger :as logger]
   [quanta.blotter.oms.validation.flow :as vf]
   [quanta.blotter.oms.flow.open-positions :as op]
   [quanta.blotter.oms.flow.working-orders :as wo]
   [quanta.blotter.oms.print :as print]))

(defn- log-table-flow [label raw-flow table-fn]
  (m/ap
   (loop []
     (m/amb
      (let [data (m/?> raw-flow)]
        (m/? (m/via m/blk (print/timestamped-table label (table-fn data))))
        data)
      (recur)))))

(defn- positions-log-flow
  [open-position-dict-flow]
  (log-table-flow "open positions"
                  (op/open-position-list-from-dict-flow open-position-dict-flow)
                  print/open-positions-table))

(defn- working-orders-log-flow [working-order-dict-flow]
  (log-table-flow "working orders"
                  (wo/working-order-list-from-dict-flow working-order-dict-flow)
                  print/working-orders-table))

(defn- snapshot-log-flow
  [{:keys [working-order-dict-flow open-position-dict-flow]} channel-flow]
  (util/mix
   (positions-log-flow open-position-dict-flow)
   (working-orders-log-flow working-order-dict-flow)
   (vf/bad-message-with-explaination channel-flow)))

(defn start-open-positions-working-order-logger! [oms log-file]
  (let [{:keys [working-order-dict-flow open-position-dict-flow]}
        (:trading-state oms)
        channel-flow (get-in oms [:consolidator :combined-flow])
        _ (assert working-order-dict-flow "start-open-positions-working-order-logger! needs :trading-state")
        l (logger/create-logger log-file false)
        log-f (snapshot-log-flow {:working-order-dict-flow working-order-dict-flow
                                  :open-position-dict-flow open-position-dict-flow}
                                 channel-flow)
        dispose! (logger/start-log-flow-to-logger l log-f)]
    {:dispose dispose!}))