(ns demo.log
  (:require
   [quanta.blotter.logger :refer [create-logger log stop-logger]]))



(def l (create-logger "test-log.txt"))


(log l "hello")
(log l "hello")
(log l "hello")
(log l "hello")

(stop-logger l)

