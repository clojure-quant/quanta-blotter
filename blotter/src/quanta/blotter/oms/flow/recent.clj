(ns quanta.blotter.oms.flow.recent
  (:require
   [missionary.core :as m]
   [quanta.missionary :refer [mix-tagged]]
   ))

(defn delayed-flow [f delay-ms]
  (m/ap
   (let [v (m/?> f)]
     (m/? (m/sleep delay-ms))
     v)))

(defn recent-flow [f delay-ms id-fn] 
  (let [delayed-f (delayed-flow f delay-ms)
        mixed-f (mix-tagged {:enter f 
                             :exit delayed-f})
        recent-exited-f (m/ap 
                         (let [recent-dict (atom {})
                               [dir data] (m/?> mixed-f)
                               id (id-fn data)]
                           (case dir 
                             :enter (swap! recent-dict assoc id data)
                             :exit (swap! recent-dict dissoc id))
                           @recent-dict))]
    recent-exited-f))