(ns quanta.blotter.oms.trading-state-consumer-test
  (:require
   [clojure.test :refer :all]
   [missionary.core :as m]
   [quanta.missionary.time-flow :refer [create-time-flow]]
   [quanta.blotter.oms.flow.trading-state :as trading-state]
   [quanta.blotter.oms.trading-state-consumer :as tsc]
   [quanta.blotter.oms.print :as print]))

(def channel-paper-time-flow
  (create-time-flow
   [1 {:type :trader/new-order, :account/id 1, :order-id 1, :asset "BTCUSDT", :side :buy, :order-type :limit, :limit 100.0, :qty 0.001}
    1 {:date #inst "2026-06-01T20:10:07.740265349Z", :order-type :limit, :limit 100.0, :account/id 1, :type :broker/order-confirmed, :order-id 1, :side :buy, :qty 0.001, :asset "BTCUSDT"}
    1 {:type :trader/new-order, :account/id 2, :order-id 2, :asset "ETHUSDT", :side :sell, :order-type :limit, :limit 100.0, :qty 0.001}
    1 {:date #inst "2026-06-01T20:10:09.740517009Z", :order-type :limit, :limit 100.0, :account/id 2, :type :broker/order-confirmed, :order-id 2, :side :sell, :qty 0.001, :asset "ETHUSDT"}
    1 {:type :trader/cancel-order, :account/id 2, :order-id 2, :asset "ETHUSDT"}
    1 {:type :broker/cancel-confirmed, :account/id 2, :order-id 2}
    1 {:order-id 2, :date #inst "2026-06-01T20:10:12.740853585Z", :type :broker/order-canceled}
    1 {:type :trader/new-order, :account/id 2, :order-id 3, :asset "ETHUSDT", :side :sell, :order-type :limit, :limit 100.0, :qty 0.001}
    1 {:date #inst "2026-06-01T20:10:17.741032902Z", :order-type :limit, :limit 100.0, :account/id 2, :type :broker/order-confirmed, :order-id 3, :side :sell, :qty 0.001, :asset "ETHUSDT"}
    1 {:type :trader/new-order, :account/id 2, :order-id 4, :asset "ETHUSDT", :side :sell, :order-type :limit, :limit 100.0, :qty 0.001}
    1 {:date #inst "2026-06-01T20:10:24.741005992Z", :order-type :limit, :limit 100.0, :account/id 2, :type :broker/order-confirmed, :order-id 4, :side :sell, :qty 0.001, :asset "ETHUSDT"}
    1 {:type :broker/order-filled, :account/id 2, :order-id 4, :fill-id "m-9By0", :date #inst "2026-06-01T20:10:29.741914267Z", :asset "ETHUSDT", :qty 0.001, :side :sell, :price 100.0}
    1 {:type :broker/order-filled, :account/id 2, :order-id 3, :fill-id "7N-G_C", :date #inst "2026-06-01T20:10:37.742482333Z", :asset "ETHUSDT", :qty 0.001, :side :sell, :price 101.0}
    1 {:type :broker/order-filled, :account/id 1, :order-id 1, :fill-id "KKEY9v", :date #inst "2026-06-01T20:10:52.742779027Z", :asset "BTCUSDT", :qty 0.001, :side :buy, :price 10000.0}]))

(defn- start-collecting! [flow acc]
  ((m/reduce (fn [_ v] (swap! acc conj v) nil) nil flow)
   (fn [_] nil)
   (fn [e]
     (when-not (instance? missionary.Cancelled e)
       (throw e)))))

(defn- stop-collecting! [dispose!]
  (try (dispose!) (catch missionary.Cancelled _)))

(defn- last-snapshot [acc]
  (last (remove nil? @acc)))

(deftest two-snapshot-consumers-see-same-trading-state
  (let [channel-flow (m/stream channel-paper-time-flow)
        ts (trading-state/create-trading-state! channel-flow)
        {:keys [trading-state-a snapshot-flow]} (tsc/create-trading-state-consumer! ts)
        acc1 (atom [])
        acc2 (atom [])
        dispose1 (start-collecting! snapshot-flow acc1)
        dispose2 (start-collecting! snapshot-flow acc2)]
    (try
      ;; 14 messages × 1ms + margin for positions and 250ms snapshot ticks
      (m/? (m/sleep 500))
      (let [final-a @trading-state-a
            snap1 (last-snapshot acc1)
            snap2 (last-snapshot acc2)
            n1 (count (remove nil? @acc1))
            n2 (count (remove nil? @acc2))]
        (is (pos? n1) "consumer 1 should receive snapshot updates")
        (is (pos? n2) "consumer 2 should receive snapshot updates")
        (is (= n1 n2)
            "both consumers should receive the same number of updates")
        (is (= snap1 snap2)
            "both consumers should end with the same snapshot value")
        (is (= snap1 final-a)
            "snapshot value should match :trading-state-a")
        (println "\n=== working-orders ===")
        (println (print/working-orders-table (:working-orders final-a)))
        (println "\n=== open-positions ===")
        (println (print/open-positions-table (:open-positions final-a))))
      (finally
        (stop-collecting! dispose1)
        (stop-collecting! dispose2)))))
