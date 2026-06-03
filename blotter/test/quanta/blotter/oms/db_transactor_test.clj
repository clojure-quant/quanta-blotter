(ns quanta.blotter.oms.db-transactor-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.db-transactor :as dbt]))

(def channel-events
  "A small channel flow (orders + broker messages) similar to channel-paper.edn."
  [{:type :trader/new-order :account/id 1 :order-id 1 :asset "BTCUSDT" :side :buy :qty 0.001}
   {:type :broker/order-confirmed :account/id 1 :order-id 1 :asset "BTCUSDT" :side :buy :qty 0.001}
   {:type :trader/new-order :account/id 2 :order-id 2 :asset "ETHUSDT" :side :sell :qty 0.001}
   {:type :broker/order-filled :account/id 2 :order-id 2 :fill-id "f-1" :asset "ETHUSDT" :side :sell :qty 0.001 :price 100.0}
   {:type :broker/order-filled :account/id 1 :order-id 1 :fill-id "f-2" :asset "BTCUSDT" :side :buy :qty 0.001 :price 10000.0}])

(deftest transactor-persists-channel-flow
  (let [conn (db/trade-db-start-mem)
        ;; fake "this" exposing a finite combined-flow
        this {:consolidator {:combined-flow (m/seed channel-events)}}]
    (m/? (dbt/transact-task this conn))
    (testing "messages persisted"
      (is (= (count channel-events) (count (db/query-messages conn)))))
    (testing "orders persisted (one per order-id)"
      (let [orders (db/query-orders conn)]
        (is (= 2 (count orders)))
        (is (= #{"1" "2"} (set (map :order/id orders))))))
    (testing "fills persisted once with order ref"
      (let [fills (db/query-fills conn)]
        (is (= 2 (count fills)))
        (is (every? :fill/order fills) "every fill references its order")))
    (testing "positions persisted"
      (is (seq (db/query-positions conn))))
    (db/trade-db-stop conn)))
