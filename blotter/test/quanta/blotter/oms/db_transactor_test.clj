(ns quanta.blotter.oms.db-transactor-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [datahike.api :as d]
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.db-transactor :as db-transactor]
   [quanta.blotter.oms.portfolio :as portfolio]
   [quanta.util.datahike :as datahike]))

(def channel-events
  "A small channel flow (orders + broker messages) similar to channel-paper.edn."
  [{:type :trader/new-order :date #inst "2026-06-01T20:10:07.740Z" :account/id 1 :order-id 1 :asset "BTCUSDT" :side :buy :order-type :limit :limit 100.0 :qty 0.001}
   {:type :broker/order-confirmed :account/id 1 :order-id 1 :asset "BTCUSDT" :side :buy :order-type :limit :limit 100.0 :qty 0.001}
   {:type :trader/new-order :date #inst "2026-06-01T20:10:09.740Z" :account/id 2 :order-id 2 :asset "ETHUSDT" :side :sell :order-type :market :qty 0.001}
   {:type :broker/order-filled :account/id 2 :order-id 2 :fill-id "f-1" :asset "ETHUSDT" :side :sell :qty 0.001 :price 100.0}
   {:type :broker/order-filled :account/id 1 :order-id 1 :fill-id "f-2" :asset "BTCUSDT" :side :buy :qty 0.001 :price 10000.0}])

(defn- seed->tx-vector [events]
  (second
   (reduce
    (fn [[state tx] msg]
      (let [{:keys [state out-msg]} (portfolio/process-message state msg)]
        [state (into tx (db-transactor/out-msg->tx-vector out-msg))]))
    [(portfolio/empty-state) []]
    events)))

(defn- ref-eid [v]
  (if (map? v) (:db/id v) v))

(deftest transactor-persists-channel-flow
  (let [conn (datahike/db-start-mem db/schema)
        state (db/new-state)]
    (db/persist-block conn state (seed->tx-vector channel-events))
    (testing "messages persisted"
      (is (= (count channel-events) (count (db/query-messages conn)))))
    (testing "orders persisted (one per order-id)"
      (let [orders (db/query-orders conn)
            limit-order (first (filter #(= "1" (:order/id %)) orders))
            market-order (first (filter #(= "2" (:order/id %)) orders))]
        (is (= 2 (count orders)))
        (is (= #{"1" "2"} (set (map :order/id orders))))
        (is (= :limit (:order/type limit-order)))
        (is (= :market (:order/type market-order)))
        (is (== 100.0M (:order/limit limit-order)))
        (is (nil? (:order/limit market-order)))))
    (testing "fills persisted once with order ref"
      (let [fills (db/query-fills conn)]
        (is (= 2 (count fills)))
        (is (every? :fill/order fills) "every fill references its order")))
    (testing "positions persisted"
      (is (seq (db/query-positions conn))))
    (datahike/db-stop conn)))

(deftest transactor-links-account-db-refs
  (let [conn (datahike/db-start-mem db/schema)
        state (db/new-state)]
    (db/create-account conn {:account/id 1 :account/trader "a" :account/api :paper})
    (db/create-account conn {:account/id 2 :account/trader "b" :account/api :paper})
    (db/persist-block conn state (seed->tx-vector channel-events))
    (let [account-1-eid (:db/id (db/account-by-id conn 1))
          account-2-eid (:db/id (db/account-by-id conn 2))
          order-by-id (fn [id] (first (filter #(= id (:order/id %)) (db/query-orders conn))))
          fill-by-id (fn [id] (first (filter #(= id (:fill/id %)) (db/query-fills conn))))
          position-for (fn [account asset]
                         (first (filter #(and (= account (:position/account %))
                                             (= asset (:position/asset %)))
                                       (db/query-positions conn))))]
      (testing "orders reference account entities"
        (is (= account-1-eid (ref-eid (:order/account-db (order-by-id "1")))))
        (is (= account-2-eid (ref-eid (:order/account-db (order-by-id "2"))))))
      (testing "fills reference account entities"
        (is (= account-2-eid (ref-eid (:fill/account-db (fill-by-id "f-1")))))
        (is (= account-1-eid (ref-eid (:fill/account-db (fill-by-id "f-2"))))))
      (testing "positions reference account entities"
        (is (= account-2-eid (ref-eid (:position/account-db (position-for 2 "ETHUSDT")))))
        (is (= account-1-eid (ref-eid (:position/account-db (position-for 1 "BTCUSDT"))))))
      (testing "account-db refs resolve to correct :account/id"
        (doseq [eid [account-1-eid account-2-eid]]
          (is (some? (d/entity @conn eid)))))
    (datahike/db-stop conn))))

(deftest transactor-persists-both-positions-from-flip
  (let [conn (datahike/db-start-mem db/schema)
        state (db/new-state)
        old-open {:position/account 1
                  :position/asset "X"
                  :position/side :long
                  :position/hedge false
                  :position/qty-entry 100M
                  :position/qty-exit 0M
                  :position/qty-open 100M
                  :position/average-entry-price 10M
                  :position/avg-exit-price nil
                  :position/realized-pl 0M
                  :position/position-id "old"}
        closed (assoc old-open
                      :position/qty-exit 100M
                      :position/qty-open 0M
                      :position/avg-exit-price 11M
                      :position/realized-pl 100M)
        opened {:position/account 1
                :position/asset "X"
                :position/side :short
                :position/hedge false
                :position/qty-entry 10M
                :position/qty-exit 0M
                :position/qty-open 10M
                :position/average-entry-price 11M
                :position/avg-exit-price nil
                :position/realized-pl 0M
                :position/position-id "new"}
        tx (into (db-transactor/out-msg->tx-vector
                  {:positions-change [old-open]})
                 (db-transactor/out-msg->tx-vector
                  {:positions-change [closed opened]}))]
    (db/persist-block conn state tx)
    (let [positions (db/query-positions conn)]
      (is (= #{"old" "new"} (set (map :position/position-id positions))))
      (is (= 2 (count positions)))
      (is (= #{"new"}
             (set (map :position/position-id
                       (db/query-open-positions conn))))))
    (datahike/db-stop conn)))
