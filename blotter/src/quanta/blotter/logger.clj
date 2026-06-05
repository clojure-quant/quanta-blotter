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

(defn- write-batch! [filename console? events]
  (let [s (str "\r\n " (merge-events events))]
    (when console?
      (println s))
    (spit filename s :append true)))

(defn flow-logging-task [filename console? log-f]
  (let [blocked-f (time-buffered 500 log-f)
        logging-f (m/ap
                   (loop []
                     (m/amb
                      (let [v (m/?> blocked-f)]
                        (m/? (m/via m/blk (write-batch! filename console? v)))
                        v)
                      (recur))))]
    (m/reduce (fn [_r _v] nil) nil logging-f)))

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
  (let [logging-f (m/ap
                   (loop []
                     (m/amb
                      (let [v (m/?> f)]
                        (m/? (m/via m/blk (log this v)))
                        v)
                      (recur))))
        log-t (m/reduce (fn [_r _v] nil) nil logging-f)]
    (log-t #(println "flow-logger done" %) #(println "flow-logger error" %))))


