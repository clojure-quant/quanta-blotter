(ns demo.log
  (:require
   [missionary.core :as m]
   [quanta.blotter.logger :refer [create-logger log stop-logger start-log-flow-to-logger]]))


;; test manually logging to a logger

(def l (create-logger "log/test-log.txt" false))

(log l "hello")
(log l "hello")
(log l "hello")
(log l "hello")

(stop-logger l)


;; test logging a flow to a logger

(def l2 (create-logger "log/test-log2.txt" true))

(def f (m/seed [1 2 3 4 5 6 7 8 9 10]))

(start-log-flow-to-logger l2 f)

(stop-logger l2)