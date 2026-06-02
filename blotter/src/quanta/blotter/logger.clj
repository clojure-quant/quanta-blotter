(ns quanta.blotter.logger
  (:require
   [tick.core :as t]
   [missionary.core :as m]))

(defn time-buffered [duration-ms flow]
  (m/ap
   (let [restartable (second (m/?> (m/group-by {} flow)))]
     (m/? (->> (m/ap (m/amb= (m/?> restartable)
                             (m/? (m/sleep duration-ms ::end))))
               (m/eduction (take-while #(not= % ::end)))
               (m/reduce conj))))))


(defn merge-events [events]
  (->> events
       (map (fn [x] (str "\n" x)))
       (apply str)))

(defn flow-logging-task [filename console? log-f]
  (let [blocked-f (time-buffered 500 log-f)]
    (m/reduce (fn [_r v]
                (let [s (merge-events v)
                      s (str "\r\n " s)]
                        ;(println "logging events: " (count v) " data: \r\n" s)
                  (when console?
                    (println s))
                  (spit filename s :append true)))
              nil blocked-f)))

(defn start-logging-flow [filename console? log-f]
  ((flow-logging-task filename console? log-f) prn prn))

(defn create-logger [filename console?]
  (let [log-a (atom "")
        log-f (m/watch log-a)]
    {:dispose! (start-logging-flow filename console? log-f)
     :log! (fn [t]
             (reset! log-a t))}))


(defn stop-logger [this]
  (:dispose! this))

(defn log
  [this s]
  ((:log! this) s))



(defn start-log-flow-to-logger [this f]
  (assert this "start-log-flow-to-logger needs this")
  (assert f "start-log-flow-to-logger needs f")
  (let [log-t  (m/reduce (fn [_r v]
                           (log this v)
                           nil)
                         nil f)]
    (log-t #(println "flow-logger done" %) #(println "flow-logger error" %))))


