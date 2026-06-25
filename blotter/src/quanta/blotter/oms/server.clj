(ns quanta.blotter.oms.server
  (:require
   [clojure.java.io :as io]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager! stop-order-manager!]]
   [quanta.blotter.account-manager :refer [add-edn-accounts]]
   [quanta.blotter.oms.flow.print :refer [start-trading-state-logger!]]
   [quanta.blotter.oms.trading-state-consumer :as tsc]
   ; persistence
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.db-transactor :as db-transactor]
   ; side effects
   [quanta.blotter.paper.broker] ; side effect: brings in paper broker implementation
   ))

(defn start-oms-server [{:keys [log-file transaction-log-file validate? tag?
                                accounts-file trade-db-dir
                                trading-state-log-file trading-state-print-interval-ms]
                         :or {log-file "log/oms-server-trace.txt"
                              transaction-log-file "log/oms-server-transaction.txt"
                              validate? true
                              tag? true
                              trade-db-dir "trade-db-oms-server"
                              trading-state-log-file "log/oms-server-trading-state.txt"
                              trading-state-print-interval-ms 15000}}]

  (assert accounts-file "accounts-file is required")
  (let [_ (.mkdirs (io/file "log"))
        oms (create-order-manager {:log-file log-file
                                   :transaction-log-file  transaction-log-file
                                   :validate? validate?
                                   :tag? tag?})
        _ (add-edn-accounts (:account-manager oms) accounts-file)
        {:keys [trading-state-a snapshot-flow] :as tsc} (tsc/create-trading-state-consumer! (:trading-state oms))
        _ (tsc/start! tsc)
        oms (start-order-manager! oms)
        dispose-wo-op-logger (start-trading-state-logger! (:trading-state oms) trading-state-log-file trading-state-print-interval-ms false)
        trade-db (db/trade-db-start trade-db-dir)
        db-transactor (db-transactor/start-db-transactor oms trade-db)
        oms-server {:oms oms
                    :tsc tsc
                    :dispose-wo-op-logger dispose-wo-op-logger
                    :trade-db trade-db
                    :db-transactor db-transactor
                    :trading-state-a trading-state-a
                    :snapshot-flow snapshot-flow}]
    (assert (get-in oms [:combined-flow]) "oms :combined-flow is required")
    oms-server))

(defn stop-oms-server [{:keys [oms dispose-wo-op-logger trade-db db-transactor tsc]}]
  (db-transactor/stop-db-transactor db-transactor)
  (db/trade-db-stop trade-db)
  (stop-order-manager! oms)
  (tsc/stop! tsc)
  (when dispose-wo-op-logger ((:dispose dispose-wo-op-logger))))

(defn snapshot-flow [{:keys [snapshot-flow]}]
  snapshot-flow)