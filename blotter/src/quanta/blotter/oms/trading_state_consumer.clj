(ns quanta.blotter.oms.trading-state-consumer
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [missionary.core :as m]
   [quanta.missionary :refer [mix-tagged]]
   [quanta.blotter.util :as util]
   [quanta.blotter.flow.sample :as sample]
   [quanta.blotter.oms.flow.open-positions :as op]
   [quanta.blotter.oms.flow.working-orders :as wo]
   [quanta.blotter.oms.flow.recent :as recent]
   ))

(defn- state-flow
  [mixed-flow trading-state-a]
  (m/stream
   (m/eduction
    (map (fn [[k v]]
           (let [state (swap! trading-state-a assoc k (vals v))]
             state)))
    mixed-flow)))

(defn only-live-flow [{:keys [working-order-dict-flow open-position-dict-flow]} trading-state-a]
  (let [mixed-flow (m/stream (mix-tagged {:working-orders working-order-dict-flow
                                          :open-positions open-position-dict-flow}))
       ]
    (state-flow mixed-flow trading-state-a)))

(defn process-wo-op-cod-cpd-flow [a f]
  (let [state (atom {:working-orders {}
                     :open-positions {}
                     :closed-orders {}
                     :closed-positions {}
                     })]
    (m/ap
     (let [[k v] (m/?> f)]
       (case k
         :working-orders (swap! state assoc :working-orders v)
         :open-positions (swap! state assoc :open-positions v)
         :closed-order (swap! state assoc :closed-orders v)
         :closed-position (swap! state assoc :closed-positions v))
       (let [result {:working-orders (vals (merge (:working-orders @state) (:closed-orders @state)))
                     :open-positions (vals (merge (:open-positions @state) (:closed-positions @state)))}
             ]
         (reset! a result)
         result)))))

(defn live-and-recent-flow [{:keys [working-order-dict-flow open-position-dict-flow
                                    order-change-flow position-change-flow
                                    ]} trading-state-a recent-ms]
  (let [recent-order-dict-flow (recent/recent-flow (wo/closed-order-list-flow order-change-flow) recent-ms :order/id)
        pos-key-fn (fn [position] [(:position/account position) (:position/asset position)])
        cpf (op/closed-position-list-flow position-change-flow)
        recent-position-dict-flow (recent/recent-flow cpf recent-ms pos-key-fn)
        mixed-flow (m/stream (mix-tagged {:working-orders working-order-dict-flow
                                          :open-positions open-position-dict-flow
                                          :closed-order recent-order-dict-flow
                                          :closed-position recent-position-dict-flow}))]
    (process-wo-op-cod-cpd-flow trading-state-a mixed-flow)))

(defn create-trading-state-consumer! [trading-state recent-ms]
  (let [trading-state-a (atom {:working-orders [] :open-positions []})
        states-f (if (= recent-ms 0)
                 (only-live-flow trading-state trading-state-a)
                 (live-and-recent-flow trading-state trading-state-a recent-ms))
        snapshot-flow (m/stream
                       (sample/sample-continuous-on-change (util/cont states-f) 250))]
    {:trading-state-a trading-state-a
     :snapshot-flow snapshot-flow
     :dispose-a (atom nil)}))

(defn start!
  [{:keys [dispose-a snapshot-flow] :as _this}]
  (let [t (m/reduce (fn [_r _v] nil) nil snapshot-flow)
        dispose! (t
                  #(info "tranding-state-consumer done" %)
                  #(error "trading-state-consumer error" %))]
    (reset! dispose-a dispose!)))

(defn stop!
  [{:keys [dispose-a]}]
  (when-let [dispose! @dispose-a]
    (dispose!)))