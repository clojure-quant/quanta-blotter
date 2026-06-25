(ns quanta.blotter.flow.sample
  "Missionary helpers for periodic sampling of continuous flows,
   emitting only when the sampled value changes."
  (:require
   [missionary.core :as m]))

(defn tick-flow
  "Discrete flow that emits nil every `interval-ms` milliseconds."
  [interval-ms]
  (m/ap
   (loop []
     (m/amb
      (m/? (m/sleep interval-ms nil))
      (recur)))))

(defn only-when-changed
  "Wrap a discrete flow so consecutive equal values are dropped.
   The first value is always emitted."
  [flow]
  (let [prev (volatile! ::none)]
    (m/eduction
     (fn [rf]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result v]
          (if (= @prev v)
            result
            (do (vreset! prev v)
                (rf result v))))))
     flow)))

(defn sample-continuous-on-change
  "Sample `continuous-flows` every `interval-ms` via `combine-fn`,
   emitting only when the combined snapshot changes."
  [f interval-ms]
  (only-when-changed
   (m/sample (fn [snap _tick] snap)
             f
             (tick-flow interval-ms))))
