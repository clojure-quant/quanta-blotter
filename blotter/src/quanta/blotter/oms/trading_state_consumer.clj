(ns quanta.blotter.oms.trading-state-consumer
  (:require
   [missionary.core :as m]
   [quanta.missionary :refer [mix-tagged]]
   [quanta.blotter.util :as util]
   [quanta.blotter.flow.sample :as sample]))

(defn- state-flow
  [mixed-flow trading-state-a]
  (m/stream
   (m/eduction
    (map (fn [[k v]]
           (let [state (swap! trading-state-a assoc k (vals v))]
             state)))
    mixed-flow)))

(defn create-trading-state-consumer! [{:keys [working-order-dict-flow open-position-dict-flow]}]
  (let [mixed-flow (m/stream (mix-tagged {:working-orders working-order-dict-flow
                                          :open-positions open-position-dict-flow}))
        trading-state-a (atom {:working-orders [] :open-positions []})
        states (state-flow mixed-flow trading-state-a)
        snapshot-flow (m/stream
                       (sample/sample-continuous-on-change (util/cont states) 250))]
    {:trading-state-a trading-state-a
     :snapshot-flow snapshot-flow
     :dispose-a (atom nil)}))

(defn start!
  [{:keys [dispose-a snapshot-flow]}]
  (let [t (m/reduce (fn [_r _v] nil) nil snapshot-flow)
        dispose! (t
                  #(println "tranding-state-consumer done" %)
                  #(println "trading-state-consumer error" %))]
    (reset! dispose-a dispose!)))

(defn stop!
  [{:keys [dispose-a]}]
  (when-let [dispose! @dispose-a]
    (dispose!)))