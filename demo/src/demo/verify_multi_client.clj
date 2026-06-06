(ns demo.verify-multi-client
  (:require
   [quanta.blotter.cli.client :as client]
   [quanta.blotter.cli.send :as send]))

(def snapshot-fn 'quanta.blotter.oms.flow.snapshot/trading-snapshot-fn)

(defn- wait-ms [ms]
  (Thread/sleep ms))

(defn- account-7-order-count [snap]
  (count (filter #(= 7 (:order/account-id %)) (:working-orders snap))))

(defn- wait-for-account-7-orders [conn min-count timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [remaining (- deadline (System/currentTimeMillis))]
        (cond
          (not (pos? remaining))
          nil

          :else
          (if-let [snap (client/take-snapshot! conn remaining)]
            (if (>= (account-7-order-count snap) min-count)
              (do
                (println "  snapshot has" (account-7-order-count snap) "account-7 working orders")
                snap)
              (recur))
            nil))))))

(defn verify!
  "Reproduce the multi-client bug: client1 sees orders, client2 should too."
  []
  (println "Connecting client 1...")
  (let [conn1 (client/connect!)]
    (wait-ms 500)
    (client/subscribe! conn1 snapshot-fn)
    (wait-ms 500)
    (println "Sending never orders...")
    (send/send-orders! "never")
    (println "Waiting for client 1 snapshot...")
    (let [snap1 (wait-for-account-7-orders conn1 3 60000)
          count1 (if snap1 (account-7-order-count snap1) 0)]
      (println "Client 1 account-7 working orders:" count1)
      (println "Connecting client 2 (late subscriber)...")
      (let [conn2 (client/connect!)]
        (wait-ms 500)
        (client/subscribe! conn2 snapshot-fn)
        (println "Waiting for client 2 snapshot...")
        (let [snap2 (wait-for-account-7-orders conn2 3 60000)
              count2 (if snap2 (account-7-order-count snap2) 0)]
          (println "Client 2 account-7 working orders:" count2)
          (client/close! conn2)
          (println "Waiting for client 1 snapshot after client 2 connected...")
          (let [snap1-again (wait-for-account-7-orders conn1 3 10000)
                count1-again (if snap1-again (account-7-order-count snap1-again) 0)]
            (println "Client 1 account-7 working orders after client 2:" count1-again)
            (client/close! conn1)
            (and (>= count1 3) (>= count2 3) (>= count1-again 3))))))))

(defn -main [& _]
  (if (verify!)
    (println "PASS: both clients see account-7 working orders")
    (do (println "FAIL: multi-client snapshot verification")
        (System/exit 1))))
