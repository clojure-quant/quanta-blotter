(ns demo.oms-server
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :refer [send-test-order]]
   [quanta.blotter.oms.server :as oms-server]
   ; side effects
   [quanta.blotter.paper.broker] ; side effect: brings in paper broker implementation
   [fix-engine.blotter.fix-trade] ; side effect: brings in fix-trade broker implementation
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
  (let [oms-server  (oms-server/start-oms-server {:log-file "log/oms-server-trace.txt"
                                                  :transaction-log-file "log/oms-server-transaction.txt"
                                                  :validate? true
                                                  :tag? true
                                                  :accounts-file "demo-accounts.edn"})
        {:keys [oms trade-db]} oms-server
        jetty (start-socket-server oms trade-db oms-server)
        dispose-test-order-poller (start-test-order-poller! oms 3)]
    (assoc oms-server
           :jetty jetty
           :dispose-test-order-poller dispose-test-order-poller)))

(defn stop-oms-server [{:keys [oms dispose-wo-op-logger  jetty
                               dispose-test-order-poller] :as oms-server}]
  (when dispose-test-order-poller (dispose-test-order-poller))
  (when jetty (.stop jetty))
  (oms-server/stop-oms-server oms-server))

(defn -main [& _args]
  (let [oms-server (start-oms-server)
        jetty (:jetty oms-server)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do (println "Shutting down OMS server...")
                                    (stop-oms-server oms-server))))
    (println "OMS server running on http://localhost:9000 — Ctrl+C to stop")
    (.join jetty)))

(comment)

