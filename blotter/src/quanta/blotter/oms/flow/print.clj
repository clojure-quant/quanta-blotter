(ns quanta.blotter.oms.flow.print
  (:require
   [missionary.core :as m]
   [quanta.missionary :refer [mix-tagged mix]]
   [quanta.missionary.logger :as logger]
   [quanta.blotter.oms.validation.flow :as vf]
   [quanta.blotter.oms.flow.open-positions :as op]
   [quanta.blotter.oms.flow.working-orders :as wo]
   [quanta.blotter.oms.print :as print]))

(defn- log-table-flow [label raw-flow table-fn]
  (m/ap
   (let [data (m/?> raw-flow)]
     (m/? (m/via m/blk (print/timestamped-table label (table-fn data)))))))

(defn- positions-log-flow
  [open-position-dict-flow]
  (log-table-flow "open positions"
                  (op/open-position-list-from-dict-flow open-position-dict-flow)
                  print/open-positions-table))

(defn- working-orders-log-flow [working-order-dict-flow]
  (log-table-flow "working orders"
                  (wo/working-order-list-from-dict-flow working-order-dict-flow)
                  print/working-orders-table))

(defn start-open-positions-working-order-logger! [oms log-file]
  (let [{:keys [trading-state combined-flow]} oms
        _ (assert trading-state "start-open-positions-working-order-logger! needs :trading-state")
        _ (assert combined-flow "start-open-positions-working-order-logger! needs :combined-flow")
        {:keys [working-order-dict-flow open-position-dict-flow]} trading-state
        _ (assert working-order-dict-flow "start-open-positions-working-order-logger! needs :working-order-dict-flow")
        _ (assert open-position-dict-flow "start-open-positions-working-order-logger! needs :open-position-dict-flow")
        l (logger/create-logger log-file false)
        log-f  (mix (working-orders-log-flow working-order-dict-flow)
                    (positions-log-flow open-position-dict-flow)
                    (vf/bad-message-with-explaination combined-flow))
        #_log-f  #_(mix-tagged  {:working-order (working-orders-log-flow working-order-dict-flow)
                                 :open-position (positions-log-flow open-position-dict-flow)
                                 :bad-message (vf/bad-message-with-explaination channel-flow)})
        dispose! (logger/start-log-flow-to-logger l log-f)]
    {:dispose dispose!}))