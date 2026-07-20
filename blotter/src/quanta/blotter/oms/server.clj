(ns quanta.blotter.oms.server
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [clojure.java.io :as io]
   [modular.require :refer [require-namespaces]]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager! stop-order-manager!]]
   [quanta.blotter.account-manager :refer [add-enabled-db-accounts]]
   [quanta.blotter.oms.flow.print :refer [start-trading-state-logger!]]
   [quanta.blotter.oms.trading-state-consumer :as tsc]
   [quanta.blotter.oms.trader :as trader]
   [quanta.blotter.oms.db-transactor :as db-transactor]
   [quanta.util.datahike :as datahike]
   ; side effects
   [quanta.market-sim.broker-paper] ; side effect: brings in paper broker implementation
   ))

(defn- require-config-namespaces! [ns-require]
  (when (seq ns-require)
    (info "requiring namespaces:" (pr-str ns-require))
    (require-namespaces ns-require)))

; (vf/bad-message-with-explaination combined-flow)

(defn start-oms-server
  ([config] (start-oms-server config nil))
  ([config trade-db]
   (let [{:keys [log-file transaction-log-file account-log-dir validate? tag?
                 ns-require
                 trading-state-log-file trading-state-print-interval-ms
                 ui-recent-ms
                 ctx]
          :or {log-file "log/oms-server-trace.txt"
               transaction-log-file "log/oms-server-transaction.txt"
               account-log-dir "log/oms-account"
               validate? true
               tag? true
               trading-state-log-file "log/oms-server-trading-state.txt"
               trading-state-print-interval-ms 15000
               ui-recent-ms 60000}} config]
     (assert trade-db "trade-db connection is required")
     (require-config-namespaces! ns-require)
     (let [_ (.mkdirs (io/file "log"))
           _ (.mkdirs (io/file account-log-dir))
           oms (create-order-manager {:log-file log-file
                                      :transaction-log-file transaction-log-file
                                      :account-log-dir account-log-dir
                                      :validate? validate?
                                      :tag? tag?
                                      :ctx ctx})
           _ (add-enabled-db-accounts (:account-manager oms) trade-db)
           {:keys [trading-state-a snapshot-flow] :as tsc} (tsc/create-trading-state-consumer! (:trading-state oms) ui-recent-ms)
           _ (tsc/start! tsc)
           {:keys [trading-state-trader] :as trader-tagger} (trader/start-trader-tagger trade-db trading-state-a)
           oms (start-order-manager! oms)
           
           dispose-wo-op-logger (start-trading-state-logger! (:trading-state oms) trading-state-log-file trading-state-print-interval-ms false)
           db-transactor (db-transactor/start-db-transactor oms trade-db)
           oms-server {:oms oms
                       :tsc tsc
                       :trader-tagger trader-tagger
                       :dispose-wo-op-logger dispose-wo-op-logger
                       :trade-db trade-db
                       :db-transactor db-transactor
                       :trading-state-a trading-state-a
                       :trading-state-trader trading-state-trader
                       :snapshot-flow snapshot-flow}]
       (assert (get-in oms [:combined-flow]) "oms :combined-flow is required")
       oms-server))))

(defn stop-oms-server [{:keys [oms dispose-wo-op-logger trade-db db-transactor tsc trader-tagger]}]
  (db-transactor/stop-db-transactor db-transactor)
  (when-let [dispose! (:dispose! trader-tagger)] (dispose!))
  (datahike/db-stop trade-db)
  (stop-order-manager! oms)
  (tsc/stop! tsc)
  (when dispose-wo-op-logger (dispose-wo-op-logger)))

(defn snapshot-flow [{:keys [snapshot-flow]}]
  snapshot-flow)