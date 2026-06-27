(ns demo.test-order-sender
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.core :refer [send-test-order]]))

(defn start-order-poller
  "Send a test order on `account-id` immediately, then every
   `:interval-sec` seconds. Returns a dispose fn."
  [oms {:keys [account-id interval-sec] :or {interval-sec 30}}]
  (let [task (m/sp
              (loop []
                (m/? (send-test-order oms account-id))
                (m/? (m/sleep (* 1000 interval-sec)))
                (recur)))]
    (task #(println "test-order poller done" %)
          #(println "test-order poller error" %))))

(defn stop-order-poller [dispose]
  (when dispose (dispose)))
