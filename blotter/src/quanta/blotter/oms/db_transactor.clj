(ns quanta.blotter.oms.db-transactor
  (:require
   [missionary.core :as m]
   [taoensso.timbre :refer [info error]]
   [quanta.blotter.util :as util]
   [quanta.blotter.logger :as logger]
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.flow.fill :as fill]
   [quanta.blotter.oms.flow.open-positions :as op]
   [quanta.blotter.oms.flow.working-orders :as wo]))

(def ^:private buffer-ms 500)

(defn- tag [k flow]
  (m/eduction (map (fn [v] {k v})) flow))

(defn- tagged-flows
  "Builds the four tagged flows derived from the channel flow."
  [channel-flow]
  (let [fill-flow (fill/fill-flow channel-flow)]
    [(tag :msg channel-flow)
     (tag :order (wo/order-change-flow channel-flow))
     (tag :fill fill-flow)
     (tag :position (op/position-change-flow fill-flow {:method :fifo}))]))

(defn- block->tx-vector
  "Turns a buffered block (vector of single-entry tagged maps like {:msg m})
   into the flat [:msg m :order o ...] vector expected by db/process."
  [block]
  (into [] (mapcat (fn [m] (first (seq m))) block)))

(defn transact-task
  "Missionary task that persists all OMS flows of `this` into `db`.
   Writes are buffered into time blocks and processed together."
  [this db]
  (let [channel-flow (get-in this [:consolidator :combined-flow])
        _ (assert channel-flow "start-db-transactor needs a consolidator combined-flow")
        state (db/new-state)
        combined (apply util/mix (tagged-flows channel-flow))
        buffered (logger/time-buffered buffer-ms combined)]
    (m/reduce
     (fn [_ block]
       (let [tx-vector (block->tx-vector block)]
         (info "db-transactor writing block of" (count block) "events")
         (db/process db state tx-vector)
         nil))
     nil
     buffered)))

(defn start-db-transactor
  "Starts persisting the OMS flows of `this` (from create-order-manager) into
   `db` (a datahike connection from quanta.blotter.oms.db/trade-db-start).
   Returns a map with a :dispose! fn."
  [this db]
  (assert this "start-db-transactor needs the order-manager (this)")
  (assert db "start-db-transactor needs a db connection")
  (info "starting db-transactor ..")
  (let [dispose! ((transact-task this db)
                  #(info "db-transactor done" %)
                  #(error "db-transactor error" %))]
    {:dispose! dispose!
     :db db}))

(defn stop-db-transactor [{:keys [dispose!]}]
  (info "stopping db-transactor ..")
  (when dispose! (dispose!)))
