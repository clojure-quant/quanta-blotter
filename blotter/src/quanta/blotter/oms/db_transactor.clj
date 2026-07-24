(ns quanta.blotter.oms.db-transactor
  (:require
   [missionary.core :as m]
   [taoensso.timbre :refer [info error]]
   [quanta.missionary.logger :as logger]
   [quanta.blotter.util :as util]
   [quanta.blotter.oms.db :as db]))

(def ^:private buffer-ms 500)

(defn- tag [k flow]
  (m/eduction (map (fn [v] {k v})) flow))

(defn- tagged-flows
  "Builds the four tagged flows from shared trading-state flows."
  [{:keys [combined-flow trading-state] :as oms}]
  (let [{:keys [order-change-flow fill-flow position-change-flow]} trading-state]
    [(tag :msg combined-flow)
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
    (db/process db state tx-vector)
    (info "db-transactor wrote block of" (count block) "events")))

(defn transact-task
  "Missionary task that persists all OMS flows of `this` into `db`.
   Writes are buffered into time blocks and processed together."
  [oms db cancel-rdv]
  (let [_ (assert (:trading-state oms) "start-db-transactor needs :trading-state")
        state (db/new-state)
        combined (apply util/mix (tagged-flows oms))
        ; (logger/time-buffered buffer-ms combined)
        buffered (logger/time-buffered-cancellable buffer-ms cancel-rdv combined)
        transacting-f (m/ap
                       (let [block (m/?> buffered)]
                         (m/? (m/via m/blk (write-block! db state block)))
                         block))]
    (m/reduce (fn [_r _v] nil) nil transacting-f)))

(defn start-db-transactor
  "Starts persisting the OMS flows of `this` (from create-order-manager) into
   `db` (a datahike connection from quanta.util.datahike/db-start).
   Returns a map with a :dispose! fn."
  [oms db]
  (assert oms "start-db-transactor needs the order-manager (oms)")
  (assert db "start-db-transactor needs a db connection")
  (info "starting db-transactor ..")
  (let [cancel-rdv (m/rdv)
        dispose-transactor! ((transact-task oms db cancel-rdv)
                             #(info "db-transactor done" %)
                             #(error "db-transactor error" %))
        dispose! (fn []
                   ((cancel-rdv :quanta.missionary.logger/end)
                    (fn [_]
                      (info "db transactor received the timeout signal.")
                   )
                    (fn [ex]
                      (error "db transactor timeout signal ex: " ex))))]
    {:dispose-transactor! dispose-transactor!
     :dispose! dispose!
     :db db}))

(defn stop-db-transactor [{:keys [dispose!]}]
  (info "stopping db-transactor ..")
  (when dispose! 
    (dispose!)
    (Thread/sleep 1000) ; give it time to finish flushing.
    )
    
  )
