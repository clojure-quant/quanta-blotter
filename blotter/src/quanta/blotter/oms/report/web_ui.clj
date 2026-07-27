(ns quanta.blotter.oms.report.web-ui
  (:require
   [taoensso.timbre :refer [info error]]
   [missionary.core :as m]
   [quanta.blotter.util :as util]
   [quanta.blotter.flow.sample :as sample]
   [quanta.blotter.oms.flow.recent :as recent]
   [quanta.blotter.oms.portfolio.working-order :as wo]))

(defn- dict->vals [dict]
  (vec (vals (or dict {}))))

(defn- live-snapshot [{:keys [working-order open-position]}]
  {:working-orders (dict->vals working-order)
   :open-positions (dict->vals open-position)})

(defn only-live-flow
  "Emit snapshot whenever the portfolio public state atom changes."
  [portfolio trading-state-a]
  (let [state-f (m/watch (:state portfolio))]
    (m/eduction
     (map (fn [public]
            (let [snap (live-snapshot public)]
              (reset! trading-state-a snap)
              snap)))
     state-f)))

(defn live-and-recent-flow
  "Live working/open dicts plus recently closed orders/positions."
  [portfolio trading-state-a recent-ms]
  (let [out-flow (:out-flow portfolio)
        closed-order-f (m/eduction
                        (keep (fn [out]
                                (when-let [o (:order out)]
                                  (when (wo/order-done? o) o))))
                        out-flow)
        closed-position-f (m/eduction
                           (keep (fn [out]
                                   (when-let [p (:position-change out)]
                                     (when (false? (:position/open p)) p))))
                           out-flow)
        recent-order-dict-flow (recent/recent-flow closed-order-f recent-ms :order/id)
        pos-key-fn (fn [position] [(:position/account position) (:position/asset position)])
        recent-position-dict-flow (recent/recent-flow closed-position-f recent-ms pos-key-fn)
        state-f (m/watch (:state portfolio))
        combined (m/ap
                  (m/amb=
                   (let [public (m/?> state-f)]
                     [:live public])
                   (let [d (m/?> recent-order-dict-flow)]
                     [:closed-orders d])
                   (let [d (m/?> recent-position-dict-flow)]
                     [:closed-positions d])))
        acc (atom {:live {:working-order {} :open-position {}}
                   :closed-orders {}
                   :closed-positions {}})]
    (m/ap
     (let [[k v] (m/?> combined)]
       (case k
         :live (swap! acc assoc :live v)
         :closed-orders (swap! acc assoc :closed-orders v)
         :closed-positions (swap! acc assoc :closed-positions v))
       (let [{:keys [live closed-orders closed-positions]} @acc
             result {:working-orders (vec (vals (merge (:working-order live) closed-orders)))
                     :open-positions (vec (vals (merge (:open-position live) closed-positions)))}]
         (reset! trading-state-a result)
         result)))))

(defn create-trading-state-consumer!
  "Build UI snapshot consumer from a portfolio map (:state + :out-flow)."
  [portfolio recent-ms]
  (let [trading-state-a (atom {:working-orders [] :open-positions []})
        states-f (if (= recent-ms 0)
                   (only-live-flow portfolio trading-state-a)
                   (live-and-recent-flow portfolio trading-state-a recent-ms))
        snapshot-flow (m/stream
                       (sample/sample-continuous-on-change (util/cont states-f) 250))]
    {:trading-state-a trading-state-a
     :snapshot-flow snapshot-flow
     :dispose-a (atom nil)}))

(defn start!
  [{:keys [dispose-a snapshot-flow] :as _this}]
  (let [t (m/reduce (fn [_r _v] nil) nil snapshot-flow)
        dispose! (t
                  #(info "trading-state-consumer done" %)
                  #(error "trading-state-consumer error" %))]
    (reset! dispose-a dispose!)))

(defn stop!
  [{:keys [dispose-a]}]
  (when-let [dispose! @dispose-a]
    (dispose!)))
