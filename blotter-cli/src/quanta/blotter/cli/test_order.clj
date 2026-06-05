(ns quanta.blotter.cli.test-order
  "RPC smoke test: call demo.oms-server/send-test-order over the flowy websocket."
  (:require
   [quanta.blotter.cli.client :as client]))

(def send-test-order-fn 'quanta.blotter.oms.core/send-test-order)

(defn test-order!
  "Connect, call send-test-order (uses server-side oms-server ctx), print result."
  ([] (test-order! "ws://localhost:9000/flowy"))
  ([ws-url]
   (let [conn (client/connect! ws-url)]
     (Thread/sleep 500)
     (try
       (println (client/request-sync! conn send-test-order-fn [3] 10000))
       (finally
         (client/close! conn))))))
