(ns quanta.blotter.oms.flow.print
  (:require
   [missionary.core :as m]
   [quanta.blotter.util :as util]
   [quanta.blotter.logger :as logger]
   [quanta.blotter.oms.validation.flow :as vf]
   [quanta.blotter.oms.flow.fill :as fill]
   [quanta.blotter.oms.flow.open-positions :as op]
   [quanta.blotter.oms.flow.working-orders :as wo]
   [quanta.blotter.oms.print :as print]
   ))


(defn- log-table-flow [label raw-flow table-fn]
  (m/ap
   (loop []
     (m/amb
      (let [data (m/?> raw-flow)]
        (m/? (m/via m/blk (print/timestamped-table label (table-fn data))))
        data)
      (recur)))))

(defn- positions-log-flow
  [channel-flow & [{:keys [method] :or {method :fifo}}]]
  (log-table-flow "open positions"
                  (op/open-position-list-flow
                   (op/position-change-flow (fill/fill-flow channel-flow) {:method method}))
                  print/open-positions-table))

(defn- working-orders-log-flow [channel-flow]
  (log-table-flow "working orders"
                  (wo/working-order-list-flow
                   (wo/order-change-flow channel-flow))
                  print/working-orders-table))

(defn- snapshot-log-flow
  [channel-flow & [{:keys [method] :or {method :fifo}}]]
  (util/mix
   (positions-log-flow channel-flow {:method method})
   (working-orders-log-flow channel-flow)
   (vf/bad-message-with-explaination channel-flow)))

(defn start-open-positions-working-order-logger! [oms log-file]
  (let [channel-flow (get-in oms [:consolidator :combined-flow])
        _ (assert channel-flow "start-open-positions-working-order-logger! needs channel-flow")
        l (logger/create-logger log-file false)
        log-f (snapshot-log-flow channel-flow {:method :fifo})
        dispose! (logger/start-log-flow-to-logger l log-f)]
    {:dispose dispose!}))