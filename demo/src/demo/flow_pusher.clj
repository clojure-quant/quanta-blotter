(ns demo.flow-pusher
  "Demonstrate periodic sampling of continuous flows with change-only emission.

  Run from the demo directory:
    clojure -X:flow-pusher-demo

  Two synthetic sources change at different intervals. A 250ms sampler
  prints snapshots only when {:a ... :b ...} actually changes."
  (:require
   [missionary.core :as m]
   [quanta.blotter.flow.sample :as sample]
   [quanta.blotter.util :as util]
   [tick.core :as t]))

(defn- changing-flow
  "Emit values from `values` after each corresponding delay in `delays-ms`."
  [delays-ms values]
  (m/ap
   (loop [pairs (map vector delays-ms values)]
     (when (seq pairs)
       (m/amb
        (let [[d v] (first pairs)]
          (m/? (m/sleep d v)))
        (recur (rest pairs)))))))

(defn run-demo!
  [& _]
  (println "flow-pusher demo — 250ms sample, change-only emission")
  (println "expect ~4 emissions as :a and :b change independently\n")
  (let [a-f (changing-flow [100 500 200 800] [:a1 :a2 :a3 :a4 :a5])
        b-f (changing-flow [300 700 100 600] [:b1 :b2 :b3 :b4 :b5])
        a-cont (util/cont a-f)
        b-cont (util/cont b-f)
        snap-f (sample/sample-continuous-on-change
                250 (fn [a b] {:a a :b b}) a-cont b-cont)
        limited (m/eduction (take 6) snap-f)]
    (m/? (m/reduce (fn [_ v]
                     (println (t/instant) "emit:" (pr-str v))
                     nil)
                   nil
                   limited))
    (println "\ndone.")))
