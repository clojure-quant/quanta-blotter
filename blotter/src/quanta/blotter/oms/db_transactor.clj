(ns quanta.blotter.oms.db-transactor
  (:require
   [missionary.core :as m]
   [taoensso.timbre :refer [info error]]
   [quanta.blotter.util :as util]
   [quanta.blotter.logger :as logger]
   [quanta.blotter.oms.db :as db]))

(def ^:private buffer-ms 500)

(defn- tag [k flow]
  (m/eduction (map (fn [v] {k v})) flow))

(defn- tagged-flows
  "Builds the four tagged flows from shared trading-state flows."
  [{:keys [consolidator trading-state]}]
  (let [{:keys [order-change-flow fill-flow position-change-flow]} trading-state
        channel-flow (:combined-flow consolidator)]
    [(tag :msg channel-flow)
     (tag :order order-change-flow)
     (tag :fill fill-flow)
     (tag :position position-change-flow)]))

(defn- block->tx-vector
  "Turns a buffered block (vector of single-entry tagged maps like {:msg m})
   into the flat [:msg m :order o ...] vector expected by db/process."
  [block]
  (into [] (mapcat (fn [m] (first (seq m))) block)))

(defn- write-block! [db state block]
  (let [tx-vector (block->tx-vector block)]
    (info "db-transactor writing block of" (count block) "events")
    (db/process db state tx-vector)))

(defn transact-task
  "Missionary task that persists all OMS flows of `this` into `db`.
   Writes are buffered into time blocks and processed together."
  [this db]
  (let [_ (assert (:trading-state this) "start-db-transactor needs :trading-state")
        state (db/new-state)
        combined (apply util/mix (tagged-flows this))
        buffered (logger/time-buffered buffer-ms combined)
        transacting-f (m/ap
                       (loop []
                         (m/amb
                          (let [block (m/?> buffered)]
                            (m/? (m/via m/blk (write-block! db state block)))
                            block)
                          (recur))))]
    (m/reduce (fn [_r _v] nil) nil transacting-f)))

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
