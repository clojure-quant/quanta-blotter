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


(defn start-logging-flow [filename log-f]
  (let [blocked-f (time-buffered 500 log-f)
        t (m/reduce (fn [_r v]
                      (let [s (map (fn [x] (str "\n" x)) v)
                            s (apply str s)]
                        (println s)
                        (spit filename s :append true)))
                    nil blocked-f)]
    (t prn prn)))

(defn create-logger [filename]
  (let [log-a (atom "")
        log-f (m/watch log-a)]
    {:dispose! (start-logging-flow filename log-f)
     :log! (fn [t]
             (reset! log-a t))}))


(defn stop-logger [this]
  (:dispose! this))

(defn log [this s]
  ((:log! this) s))

