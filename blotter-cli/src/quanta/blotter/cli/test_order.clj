(ns quanta.blotter.cli.test-order
  "RPC smoke test: call quanta.blotter.oms.core/send-test-order over the flowy websocket."
  (:require
   [quanta.blotter.cli.client :as client]))

(def send-test-order-fn 'quanta.blotter.oms.core/send-test-order)

(defn test-order!
  "Connect, call send-test-order, print the result, and exit."
  ([] (test-order! "ws://localhost:9000/flowy"))
  ([ws-url]
   (let [conn (client/connect! ws-url)]
     (Thread/sleep 500)
     (try
       (println (client/request-sync! conn send-test-order-fn [3]))
       (finally
         (client/close! conn))))))
