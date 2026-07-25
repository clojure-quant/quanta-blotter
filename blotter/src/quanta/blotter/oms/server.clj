(ns quanta.blotter.oms.server
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [clojure.java.io :as io]
   [modular.require :refer [require-namespaces]]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager! stop-order-manager!]]
   [quanta.blotter.account-manager :refer [add-enabled-db-accounts]]
   [quanta.blotter.oms.report.text-logger :refer [start-trading-state-logger!]]
   [quanta.blotter.oms.report.web-ui :as tsc]
   [quanta.blotter.oms.report.trader :as trader]
   [quanta.blotter.oms.db-transactor :as db-transactor]))

(defn- require-config-namespaces! [ns-require]
  (when (seq ns-require)
    (info "requiring namespaces:" (pr-str ns-require))
    (require-namespaces ns-require)))

; (vf/bad-message-with-explaination combined-flow)

(defn start-oms-server
  ([config] (start-oms-server config nil))
  ([config trade-db]
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
           tsc (when calculate-enabled
                 (tsc/create-trading-state-consumer! (:trading-state oms) history-recent-ms))
           _ (when tsc (tsc/start! tsc))
           trader-tagger (when (and calculate-trading-state-trader tsc)
                           (trader/start-trader-tagger trade-db (:trading-state-a tsc)))
           oms (start-order-manager! oms)

           dispose-wo-op-logger (when print-enabled
                                  (start-trading-state-logger! (:trading-state oms) log-file interval-ms false))
           db-transactor (when db-enabled
                           (db-transactor/start-db-transactor oms trade-db))
           oms-server {:oms oms
                       :internal {:tsc tsc
                                  :trader-tagger trader-tagger
                                  :dispose-wo-op-logger dispose-wo-op-logger
                                  :trade-db trade-db
                                  :db-transactor db-transactor}}]
       (assert (get-in oms [:combined-flow]) "oms :combined-flow is required")
       oms-server))))

(defn stop-oms-server [{:keys [oms internal]}]
  (let [{:keys [dispose-wo-op-logger db-transactor tsc trader-tagger]} internal]
    (when db-transactor
      (db-transactor/stop-db-transactor db-transactor))
    (when-let [dispose! (:dispose! trader-tagger)] (dispose!))
    (stop-order-manager! oms)
    (when tsc (tsc/stop! tsc))
    (when dispose-wo-op-logger (dispose-wo-op-logger))))

(defn snapshot-flow [state]
  (get-in state [:internal :tsc :snapshot-flow]))

(defn trading-state-trader [state]
  (get-in state [:internal :trader-tagger :trading-state-trader]))
