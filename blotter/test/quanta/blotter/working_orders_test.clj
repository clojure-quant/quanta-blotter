(ns quanta.blotter.working-orders-test
  (:require
   [clojure.test :refer :all]
   [missionary.core :as m]
   [quanta.blotter.working-orders :as wo]))

(def channel-paper-flow
  (m/seed
   [{:type :trader/new-order, :account/id 1, :order-id 1, :asset "BTCUSDT", :side :buy, :limit 100.0, :qty 0.001}
    {:date #inst "2026-06-01T20:10:07.740265349Z", :limit 100.0, :account/id 1, :type :broker/order-confirmed, :order-id 1, :side :buy, :qty 0.001, :asset "BTCUSDT"}
    {:type :trader/new-order, :account/id 2, :order-id 2, :asset "ETHUSDT", :side :sell, :limit 100.0, :qty 0.001}
    {:date #inst "2026-06-01T20:10:09.740517009Z", :limit 100.0, :account/id 2, :type :broker/order-confirmed, :order-id 2, :side :sell, :qty 0.001, :asset "ETHUSDT"}
    {:type :trader/cancel-order, :account/id 2, :order-id 2}
    {:type :broker/cancel-confirmed, :account/id 2, :order-id 2}
    {:order-id 2, :date #inst "2026-06-01T20:10:12.740853585Z", :type :broker/order-canceled}
    {:type :trader/new-order, :account/id 2, :order-id 3, :asset "ETHUSDT", :side :sell, :limit 100.0, :qty 0.001}
    {:date #inst "2026-06-01T20:10:17.741032902Z", :limit 100.0, :account/id 2, :type :broker/order-confirmed, :order-id 3, :side :sell, :qty 0.001, :asset "ETHUSDT"}
    {:type :trader/new-order, :account/id 2, :order-id 4, :asset "ETHUSDT", :side :sell, :limit 100.0, :qty 0.001}
    {:date #inst "2026-06-01T20:10:24.741005992Z", :limit 100.0, :account/id 2, :type :broker/order-confirmed, :order-id 4, :side :sell, :qty 0.001, :asset "ETHUSDT"}
    {:type :broker/order-filled, :order-id 4, :fill-id "m-9By0", :date #inst "2026-06-01T20:10:29.741914267Z", :asset "ETHUSDT", :qty 0.001, :side :sell, :price 100.0}
    {:type :broker/order-filled, :order-id 3, :fill-id "7N-G_C", :date #inst "2026-06-01T20:10:37.742482333Z", :asset "ETHUSDT", :qty 0.001, :side :sell, :price 101.0}
    {:type :broker/order-filled, :order-id 1, :fill-id "KKEY9v", :date #inst "2026-06-01T20:10:52.742779027Z", :asset "BTCUSDT", :qty 0.001, :side :buy, :price 10000.0}]))

(defn- all-emissions []
  (m/? (m/reduce conj [] (wo/order-change-flow channel-paper-flow))))

(defn- emissions-by-order-id [emissions]
  (group-by :order/id emissions))

(defn- final-for-order
  "Latest state for an order (emissions are newest-first from parallel branches)."
  [emissions order-id]
  (->> emissions (filter #(= order-id (:order/id %))) first))

(defn- chronological-for-order [emissions order-id]
  (->> emissions (filter #(= order-id (:order/id %))) reverse))

(deftest order-change-flow-emits-flat-maps
  (let [emissions (all-emissions)]
    (is (every? map? emissions))
    (is (every? #(contains? % :order/id) emissions))
    (is (not-any? vector? emissions))))

(deftest incremental-emissions-per-order
  (let [by-id (emissions-by-order-id (all-emissions))]
    (is (> (count (get by-id 1)) 1))
    (is (> (count (get by-id 2)) 1))
    (is (> (count (get by-id 4)) 1))))

(deftest order-2-cancelled
  (let [emissions (all-emissions)
        last-2 (final-for-order emissions 2)]
    (is (= :done (:order/status last-2)))
    (is (= 0.0 (:order/qty-working last-2)))
    (is (= 0.0 (:order/qty-filled last-2)))
    (is (nil? (:order/avg-price last-2)))
    (is (some #(= :broker/order-canceled (:type %)) (:order/history last-2)))))

(deftest order-4-filled
  (let [emissions (all-emissions)
        last-4 (final-for-order emissions 4)]
    (is (= :done (:order/status last-4)))
    (is (= 0.001 (:order/qty-filled last-4)))
    (is (= 0.0 (:order/qty-working last-4)))
    (is (= 100.0 (:order/avg-price last-4)))))

(deftest avg-price-nil-before-fill
  (let [emissions (all-emissions)
        first-4 (first (chronological-for-order emissions 4))]
    (is (nil? (:order/avg-price first-4)))))

(deftest order-1-working-until-final-fill
  (let [emissions (all-emissions)
        order-1 (chronological-for-order emissions 1)
        before-fill (butlast order-1)
        last-1 (last order-1)]
    (is (every? #(= :working (:order/status %)) before-fill))
    (is (= :done (:order/status last-1)))
    (is (= 10000.0 (:order/avg-price last-1)))))

(deftest working-order-list-flow-keeps-open-orders-only
  (let [flow (m/seed [{:type :trader/new-order, :account/id 1, :order-id 9
                       :asset "BTCUSDT", :side :buy, :qty 0.001}])
        lists (m/? (m/reduce conj [] (wo/working-order-list-flow (wo/order-change-flow flow))))]
    (is (= 1 (count (last lists))))
    (is (= 9 (:order/id (first (last lists)))))))
