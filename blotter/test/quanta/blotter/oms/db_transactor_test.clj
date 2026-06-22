(ns quanta.blotter.oms.db-transactor-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [quanta.blotter.util :as util]
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.flow.fill :as fill]
   [quanta.blotter.oms.flow.open-positions :as op]
   [quanta.blotter.oms.flow.working-orders :as wo]))

(def channel-events
  "A small channel flow (orders + broker messages) similar to channel-paper.edn."
  [{:type :trader/new-order :account/id 1 :order-id 1 :asset "BTCUSDT" :side :buy :order-type :limit :limit 100.0 :qty 0.001}
   {:type :broker/order-confirmed :account/id 1 :order-id 1 :asset "BTCUSDT" :side :buy :order-type :limit :limit 100.0 :qty 0.001}
   {:type :trader/new-order :account/id 2 :order-id 2 :asset "ETHUSDT" :side :sell :order-type :market :qty 0.001}
   {:type :broker/order-filled :account/id 2 :order-id 2 :fill-id "f-1" :asset "ETHUSDT" :side :sell :qty 0.001 :price 100.0}
   {:type :broker/order-filled :account/id 1 :order-id 1 :fill-id "f-2" :asset "BTCUSDT" :side :buy :qty 0.001 :price 10000.0}])

(defn- seed->tx-vector [events]
  (let [flow (m/seed events)
        fill-flow (fill/fill-flow flow)
        combined (util/mix
                  (m/eduction (map (fn [v] {:msg v})) flow)
                  (m/eduction (map (fn [v] {:order v})) (wo/order-change-flow flow))
                  (m/eduction (map (fn [v] {:fill v})) fill-flow)
                  (m/eduction (map (fn [v] {:position v}))
                              (op/position-change-flow fill-flow {:method :fifo})))
        block (m/? (m/reduce conj [] combined))]
    (into [] (mapcat (fn [m] (first (seq m))) block))))

(deftest transactor-persists-channel-flow
  (let [conn (db/trade-db-start-mem)
        state (db/new-state)]
    (db/process conn state (seed->tx-vector channel-events))
    (testing "messages persisted"
      (is (= (count channel-events) (count (db/query-messages conn)))))
    (testing "orders persisted (one per order-id)"
      (let [orders (db/query-orders conn)]
        (is (= 2 (count orders)))
        (is (= #{"1" "2"} (set (map :order/id orders))))
        (is (= :limit (:order/type (first (filter #(= "1" (:order/id %)) orders)))))
        (is (= :market (:order/type (first (filter #(= "2" (:order/id %)) orders)))))))
    (testing "fills persisted once with order ref"
      (let [fills (db/query-fills conn)]
        (is (= 2 (count fills)))
        (is (every? :fill/order fills) "every fill references its order")))
    (testing "positions persisted"
      (is (seq (db/query-positions conn))))
    (db/trade-db-stop conn)))
