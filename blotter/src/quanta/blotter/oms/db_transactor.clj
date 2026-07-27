(ns quanta.blotter.oms.db-transactor
  (:require
   [missionary.core :as m]
   [taoensso.timbre :refer [info error]]
   [quanta.missionary.logger :as logger]
   [quanta.blotter.oms.db :as db]))

(def ^:private buffer-ms 500)

(defn out-msg->tx-vector
  "Project one portfolio out-msg to the flat [:msg m :order o ...] vector
   expected by db/process."
  [out-msg]
  (cond-> []
    (contains? out-msg :msg) (conj :msg (:msg out-msg))
    (contains? out-msg :order-change) (conj :order (:order-change out-msg))
    (contains? out-msg :trade) (conj :fill (:trade out-msg))
    (contains? out-msg :position-change) (conj :position (:position-change out-msg))))

(defn- block->tx-vector
  "Turns a buffered block of portfolio out-msg maps into one flat tx vector."
  [block]
  (into [] (mapcat out-msg->tx-vector) block))

(defn- write-block! [db state block]
  (let [tx-vector (block->tx-vector block)]
    (info "db-transactor writing block of" (count block) "events")
    (db/process db state tx-vector)
    (info "db-transactor wrote block of" (count block) "events")))

(defn transact-task
  "Missionary task that persists OMS portfolio out-flow of `this` into `db`.
   Writes are buffered into time blocks and processed together."
  [oms db cancel-rdv]
  (let [portfolio (:portfolio oms)
        _ (assert portfolio "start-db-transactor needs :portfolio")
        out-flow (:out-flow portfolio)
        _ (assert out-flow "start-db-transactor needs portfolio :out-flow")
        state (db/new-state)
        buffered (logger/time-buffered-cancellable buffer-ms cancel-rdv out-flow)
        transacting-f (m/ap
                       (let [block (m/?> buffered)]
                         (m/? (m/via m/blk (write-block! db state block)))
                         block))]
    (m/reduce (fn [_r _v] nil) nil transacting-f)))

(defn start-db-transactor
  "Starts persisting the OMS portfolio of `this` into `db`.
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
                      (info "db transactor received the timeout signal."))
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
    ))
