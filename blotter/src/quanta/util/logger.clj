(ns quanta.util.logger
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

(defn start-logging-flow [filename f]
  (let [blocked-f (time-buffered 500 f)
        t (m/reduce (fn [r v]
                      (let [s (map (fn [x] (str "\n" x)) v)
                            s (apply str s)]
                        (println "logging: " s)
                        (spit filename s :append true))) nil blocked-f)]

    (t prn prn)))

(defn create-logger [filename]
  (let [log-a (atom "")
        log-f (m/watch log-a)]
    (start-logging-flow filename log-f)
    (fn [t]
      (reset! log-a t))))

(defn log [t data]
  (println t ": " data)
  (spit "msg.log" (str "\n" t  ": " data) :append true))

(defn log-time [t data]
  (println t ": " data)
  (spit "msg.log" (str "\n" (t/instant) " " t  ": " data) :append true))
