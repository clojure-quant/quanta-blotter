(ns demo.oms-server
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager! stop-order-manager!
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

(def test-order-interval-sec 30)

(defn start-test-order-poller!
  "Send a test order pair on `account-id` immediately, then every
   `test-order-interval-sec` seconds. Returns a dispose fn."
  [oms account-id]
  (let [task (m/sp
              (loop []
                (m/? (send-test-order oms account-id))
                (m/? (m/sleep (* 1000 test-order-interval-sec)))
                (recur)))]
    (task #(println "test-order poller done" %)
          #(println "test-order poller error" %))))

(defn start-oms-server []
  (let [oms  (create-order-manager {:log-file "log/oms-server-trace.txt"
                                    :transaction-log-file "log/oms-server-transaction.txt"
                                    :validate? true})
        _ (add-edn-accounts (:account-manager oms) "demo-accounts.edn")
        oms (start-order-manager! oms)
        dispose-wo-op-logger (start-open-positions-working-order-logger! oms "log/oms-server-wo-op.txt")
        trade-db (db/trade-db-start "trade-db-oms-server")
        db-transactor (db-transactor/start-db-transactor oms trade-db)
        oms-server {:oms oms
                    :dispose-wo-op-logger dispose-wo-op-logger
                    :trade-db trade-db
                    :db-transactor db-transactor}
        jetty (start-socket-server oms trade-db oms-server)
        dispose-test-order-poller (start-test-order-poller! oms 3)
        ]
    (assoc oms-server
           :jetty jetty
           :dispose-test-order-poller dispose-test-order-poller)
    ))


(defn stop-oms-server [{:keys [oms dispose-wo-op-logger trade-db db-transactor jetty
                               dispose-test-order-poller]}]
  (when dispose-test-order-poller (dispose-test-order-poller))
  (when jetty (.stop jetty))
  (db-transactor/stop-db-transactor db-transactor)
  (db/trade-db-stop trade-db)
  (stop-order-manager! oms)
  (when dispose-wo-op-logger ((:dispose dispose-wo-op-logger))))




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

