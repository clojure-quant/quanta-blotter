(ns quanta.blotter.cli.cookie
  "RPC smoke test: call demo.fortune-cookie/get-cookie over the flowy websocket."
  (:require
   [quanta.blotter.cli.client :as client]))

(def get-cookie-fn 'demo.fortune-cookie/get-cookie)

(defn cookie!
  "Connect, call get-cookie (no args), print the result, and exit."
  ([] (cookie! "ws://localhost:9000/flowy"))
  ([ws-url]
   (let [conn (client/connect! ws-url)]
     (Thread/sleep 500)
     (try
       (println (client/request-sync! conn get-cookie-fn))
       (finally
         (client/close! conn))))))

(def poll-interval-ms (* 120 1000))

(defn- print-cookie! [conn n]
  (println (str "#" (inc n) " " (java.time.Instant/now))
           (client/request-sync! conn get-cookie-fn)))

(defn cookie-poller!
  "Keep a websocket open: print one cookie right after connect, then every
   120 seconds. Stop with Ctrl+C."
  ([] (cookie-poller! "ws://localhost:9000/flowy"))
  ([ws-url]
   (cookie-poller! ws-url poll-interval-ms))
  ([ws-url interval-ms]
   (let [conn (client/connect! ws-url)]
     (Thread/sleep 500)
     (try
       (loop [n 0]
         (print-cookie! conn n)
         (Thread/sleep interval-ms)
         (recur (inc n)))
       (finally
         (client/close! conn))))))
