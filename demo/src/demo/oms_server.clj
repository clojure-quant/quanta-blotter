(ns demo.oms-server
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager! stop-order-manager! create-limit-order 
                                    send-test-order]]
   [quanta.blotter.account-manager :refer [add-edn-accounts]]
   [quanta.blotter.oms.flow.print :refer [start-open-positions-working-order-logger!]]
   ; persistence
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.db-transactor :as db-transactor]
   ; side effects
   [quanta.blotter.paper.broker] ; side effect: brings in paper broker implementation
   ; cli websocket server
   [quanta.blotter.cli.server :refer [start-socket-server]])
  (:import [missionary Cancelled]))



(defn start-oms-server []
  (let [oms  (create-order-manager {:log-file "log/oms-server-trace.txt"
                                    :transaction-log-file "log/oms-server-transaction.txt"})
        _ (add-edn-accounts (:account-manager oms) "demo-accounts.edn")
        dispose-wo-op-logger (start-open-positions-working-order-logger! oms "log/oms-server-wo-op.txt")
        trade-db (db/trade-db-start "trade-db")
        db-transactor (db-transactor/start-db-transactor oms trade-db)
        oms-server {:oms oms
                    :dispose-wo-op-logger dispose-wo-op-logger
                    :trade-db trade-db
                    :db-transactor db-transactor}
        jetty (do
                (start-order-manager! oms)
                (start-socket-server oms trade-db oms-server))
        ]
    (m/?
     (create-limit-order oms {:account/id 3
                              :asset "__TEST"
                              :side :buy
                              :limit 110.30M
                              :qty 10000.0M}))
  
    (m/? (send-test-order oms 3)) ; 3 is the always fill account
    ;(m/? (send-test-order oms 3))
    ;(m/? (send-test-order oms 3))
    (assoc oms-server :jetty jetty)
    ))


(defn stop-oms-server [{:keys [oms dispose-wo-op-logger trade-db db-transactor jetty]}]
  (when jetty (.stop jetty))
  (db-transactor/stop-db-transactor db-transactor)
  (db/trade-db-stop trade-db)
  (stop-order-manager! oms)
  (dispose-wo-op-logger))




(defn -main [& _args]
  (let [oms-server (start-oms-server)
        jetty (:jetty oms-server)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do (println "Shutting down OMS server...")
                                    (stop-oms-server oms-server))))
    (println "OMS server running on http://localhost:9000 — Ctrl+C to stop")
    (.join jetty)))


(comment 

  
  
  )

