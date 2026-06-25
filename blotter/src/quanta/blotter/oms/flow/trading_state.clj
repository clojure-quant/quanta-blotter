(ns quanta.blotter.oms.flow.trading-state
  "OMS-level shared trading-state flows: order/position change pipelines,
   working-order / open-position dict streams, and a shared trading snapshot."
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.flow.fill :as fill]
   [quanta.blotter.oms.flow.open-positions :as op]
   [quanta.blotter.oms.flow.working-orders :as wo]))

(defn create-trading-state!
  "Build shared trading-state flows from `oms` combined-flow and start a
   drain task so state is retained while the OMS runs.
   Returns a map with shared flows and :dispose!."
  ([channel-flow]
   (create-trading-state! channel-flow {:position-method :fifo}))
  ([channel-flow {:keys [position-method]
                  :or {position-method :fifo}}]
   (let [_ (assert channel-flow "start-trading-state! needs combined-flow")
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
                :position-method position-method
                :dispose! (atom nil)}]
     state)))
