(ns quanta.blotter.oms.flow.trading-state-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [quanta.blotter.util :refer [flow-sender]]
   [quanta.blotter.oms.flow.snapshot :as snapshot]
   [quanta.blotter.oms.flow.trading-state :as trading-state]))

(def three-working-orders
  [{:type :trader/new-order :account/id 7 :order-id "n1" :asset "USDJPY" :side :buy :order-type :limit :limit 110.30M :qty 10000M}
   {:type :broker/order-confirmed :account/id 7 :order-id "n1" :asset "USDJPY" :side :buy :order-type :limit :limit 110.30M :qty 10000M}
   {:type :trader/new-order :account/id 7 :order-id "n2" :asset "USDJPY" :side :sell :order-type :limit :limit 120.51M :qty 5000M}
   {:type :broker/order-confirmed :account/id 7 :order-id "n2" :asset "USDJPY" :side :sell :order-type :limit :limit 120.51M :qty 5000M}
   {:type :trader/new-order :account/id 7 :order-id "n3" :asset "USDJPY" :side :sell :order-type :limit :limit 120.51M :qty 5000M}
   {:type :broker/order-confirmed :account/id 7 :order-id "n3" :asset "USDJPY" :side :sell :order-type :limit :limit 120.51M :qty 5000M}])

(defn- oms-with-flow []
  (let [{:keys [flow send]} (flow-sender)]
    {:consolidator {:combined-flow flow}
     :send send}))

(defn- seed-orders! [{:keys [send]}]
  (doseq [msg three-working-orders]
    (send msg)))

(defn- start-collecting! [flow acc]
  ((m/reduce (fn [_ v] (swap! acc conj v)) nil flow)
   (fn [_] nil)
   (fn [e] (println "collect-flow error" e))))

(defn- last-dict [emissions]
  (last emissions))

(deftest shared-dict-flow-multicast-to-concurrent-subscribers
  (let [{:keys [send] :as oms} (oms-with-flow)
        trading-state (trading-state/start-trading-state! oms)
        oms (assoc oms :trading-state trading-state)
        {:keys [working-order-dict-flow dispose!]} trading-state
        acc1 (atom [])
        acc2 (atom [])
        _ (start-collecting! working-order-dict-flow acc1)
        _ (start-collecting! working-order-dict-flow acc2)]
    (try
      (seed-orders! oms)
      (m/? (m/sleep 300))
      (testing "concurrent subscribers receive the same final dict"
        (let [sub1 (last-dict @acc1)
              sub2 (last-dict @acc2)]
          (is (= 3 (count sub1)))
          (is (= 3 (count sub2)))
          (is (= (set (keys sub1)) (set (keys sub2))))))
      (finally
        (trading-state/stop-trading-state! trading-state)))))

(deftest two-snapshot-flows-see-same-working-orders
  (let [{:keys [send] :as oms} (oms-with-flow)
        trading-state (trading-state/start-trading-state! oms)
        oms (assoc oms :trading-state trading-state)
        acc1 (atom [])
        acc2 (atom [])
        snap-flow #(snapshot/trading-snapshot-flow oms {:interval-ms 50})
        _ (start-collecting! (snap-flow) acc1)
        _ (start-collecting! (snap-flow) acc2)]
    (try
      (seed-orders! oms)
      (m/? (m/sleep 500))
      (let [snap1 (last @acc1)
            snap2 (last @acc2)]
        (is (= 3 (count (:working-orders snap1))))
        (is (= 3 (count (:working-orders snap2))))
        (is (= (set (map :order/id (:working-orders snap1)))
               (set (map :order/id (:working-orders snap2))))))
      (finally
        (trading-state/stop-trading-state! trading-state)))))
