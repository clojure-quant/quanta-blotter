(ns quanta.blotter.oms.server
  (:require
   [taoensso.timbre :refer [info]]
   [clojure.java.io :as io]
   [missionary.core :as m]
   [nano-id.core :refer [nano-id]]
   [modular.require :refer [require-namespaces]]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager! stop-order-manager! send-message]]
   [quanta.blotter.account-manager :refer [add-enabled-db-accounts]]
   [quanta.blotter.oms.portfolio :as portfolio]
   [quanta.blotter.oms.report.text-logger :refer [start-trading-state-logger!]]
   [quanta.blotter.oms.report.web-ui :as tsc]
   [quanta.blotter.oms.report.trader :as trader]
   [quanta.blotter.oms.db-transactor :as db-transactor]
   [quanta.blotter.util :refer [first-match]]))

(defn- require-config-namespaces! [ns-require]
  (when (seq ns-require)
    (info "requiring namespaces:" (pr-str ns-require))
    (require-namespaces ns-require)))

; (vf/bad-message-with-explaination combined-flow)

(defn start-oms-server
  [config trade-db]
  (let [{:keys [transaction-log-file account-log-dir validate? tag?
                db-enabled
                calculate-trading-state-trader
                ns-require
                web-ui text-logger
                ctx]
         :or {validate? true
              tag? true
              db-enabled false
              calculate-trading-state-trader false
              web-ui {}
              text-logger {}}} config
        {:keys [calculate-enabled history-recent-ms]
         :or {calculate-enabled false
              history-recent-ms 60000}} web-ui
        {:keys [print-enabled log-file interval-ms]
         :or {print-enabled false
              log-file "log/oms-server-trading-state.txt"
              interval-ms 15000}} text-logger]
    (assert trade-db "trade-db connection is required")
    (require-config-namespaces! ns-require)
    (let [_ (.mkdirs (io/file "log"))
          _ (when account-log-dir
              (.mkdirs (io/file account-log-dir)))
          oms (create-order-manager {:transaction-log-file transaction-log-file
                                     :account-log-dir account-log-dir
                                     :validate? validate?
                                     :tag? tag?
                                     :ctx ctx})
          _ (add-enabled-db-accounts (:account-manager oms) trade-db)
          portfolio (portfolio/portfolio-create (:combined-flow oms) trade-db)
          ;; attach consumers before portfolio-start!
          tsc (when calculate-enabled
                (tsc/create-trading-state-consumer! portfolio history-recent-ms))
          _ (when tsc (tsc/start! tsc))
          trader-tagger (when (and calculate-trading-state-trader tsc)
                          (trader/start-trader-tagger trade-db (:trading-state-a tsc)))
          dispose-wo-op-logger (when print-enabled
                                 (start-trading-state-logger! portfolio log-file interval-ms false))
          ;; oms map carries :portfolio for db-transactor
          oms (assoc oms :portfolio portfolio)
          db-transactor (when db-enabled
                          (db-transactor/start-db-transactor oms trade-db))
          _ (portfolio/portfolio-start! portfolio)
          oms (start-order-manager! oms)
          oms-server {:oms oms
                      :portfolio portfolio
                      :internal {:tsc tsc
                                 :trader-tagger trader-tagger
                                 :dispose-wo-op-logger dispose-wo-op-logger
                                 :trade-db trade-db
                                 :db-transactor db-transactor}}]
      (assert (get-in oms [:combined-flow]) "oms :combined-flow is required")
      oms-server)))

(defn stop-oms-server [{:keys [oms portfolio internal]}]
  (let [{:keys [dispose-wo-op-logger db-transactor tsc trader-tagger]} internal]
    (when db-transactor
      (db-transactor/stop-db-transactor db-transactor))
    (when-let [dispose! (:dispose! trader-tagger)] (dispose!))
    (stop-order-manager! oms)
    (when tsc (tsc/stop! tsc))
    (when dispose-wo-op-logger (dispose-wo-op-logger))
    (when portfolio
      (portfolio/portfolio-stop! portfolio))))

(defn snapshot-flow [state]
  (get-in state [:internal :tsc :snapshot-flow]))

(defn trading-state-trader [state]
  (get-in state [:internal :trader-tagger :trading-state-trader]))

(defn make-rpc-request
  "Send `msg` on the OMS and race a portfolio out-flow `first-match` against
   send-then-timeout. Returns the matching out-msg or `::timeout`."
  [this msg p timeout-ms]
  (m/sp
   (let [oms (:oms this)
         out-flow (get-in this [:portfolio :out-flow])]
     (assert oms "oms-server needs :oms")
     (assert out-flow "oms-server portfolio needs :out-flow")
     (m/? (m/race
           (first-match p out-flow)
           (m/sp
            (m/? (send-message oms msg))
            (m/? (m/sleep timeout-ms ::timeout))))))))

(defn make-position-request
  "Request open positions for `account-id`. Returns `:broker/open-positions`
   message or `::timeout`."
  ([this account-id]
   (make-position-request this account-id 10000))
  ([this account-id timeout-ms]
   (m/sp
    (let [req-id (nano-id 8)
          msg {:type :trader/open-positions
               :account/id account-id
               :req-id req-id}
          p (fn [out-msg]
              (when-let [m (:broker/open-positions out-msg)]
                (= req-id (:req-id m))))
          result (m/? (make-rpc-request this msg p timeout-ms))]
      (if (= ::timeout result)
        ::timeout
        (:broker/open-positions result))))))

(defn make-orders-request
  "Request working orders for `account-id`. Returns `:broker/working-orders`
   message or `::timeout`."
  ([this account-id]
   (make-orders-request this account-id 10000))
  ([this account-id timeout-ms]
   (m/sp
    (let [req-id (nano-id 8)
          msg {:type :trader/working-orders
               :account/id account-id
               :req-id req-id}
          p (fn [out-msg]
              (when-let [m (:broker/working-orders out-msg)]
                (= req-id (:req-id m))))
          result (m/? (make-rpc-request this msg p timeout-ms))]
      (if (= ::timeout result)
        ::timeout
        (:broker/working-orders result))))))

(defn make-account-status-request
  "Request working orders and open positions for `account-id` in parallel.
   Returns `{:orders … :positions …}` where each value is a vector or `nil`
   on timeout."
  ([this account-id]
   (make-account-status-request this account-id 10000))
  ([this account-id timeout-ms]
   (m/join (fn [orders-msg positions-msg]
             {:orders (when-not (= ::timeout orders-msg)
                        (or (:orders orders-msg) []))
              :positions (when-not (= ::timeout positions-msg)
                           (or (:positions positions-msg) []))})
           (make-orders-request this account-id timeout-ms)
           (make-position-request this account-id timeout-ms))))
