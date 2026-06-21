(ns quanta.blotter.oms.flow.campaign-test
  (:require
   [clojure.test :refer :all]
   [missionary.core :as m]
   [quanta.blotter.oms.flow.campaign :as campaign]))

(def channel-paper-events
  [;; scalp-1 :open — filled (closed, not in wo-dict)
   {:type :trader/new-order, :account/id 1, :order-id 101, :asset "BTCUSDT", :side :buy
    :order-type :limit, :limit 100.0, :qty 0.001, :campaign "scalp-1", :label :open}
   {:date #inst "2026-06-01T20:10:07.740Z", :order-type :limit, :limit 100.0, :account/id 1
    :type :broker/order-confirmed, :order-id 101, :side :buy, :qty 0.001, :asset "BTCUSDT"}
   {:type :broker/order-filled, :order-id 101, :fill-id "s1-o1", :date #inst "2026-06-01T20:10:29.741Z"
    :asset "BTCUSDT", :qty 0.001, :side :buy, :price 100.0}

   ;; scalp-1 :close — working (stays in wo-dict)
   {:type :trader/new-order, :account/id 1, :order-id 102, :asset "ETHUSDT", :side :sell
    :order-type :limit, :limit 100.0, :qty 0.002, :campaign "scalp-1", :label :close}
   {:date #inst "2026-06-01T20:10:09.740Z", :order-type :limit, :limit 100.0, :account/id 1
    :type :broker/order-confirmed, :order-id 102, :side :sell, :qty 0.002, :asset "ETHUSDT"}

   ;; scalp-1 :open — cancelled (closed, not in wo-dict)
   {:type :trader/new-order, :account/id 1, :order-id 103, :asset "SOLUSDT", :side :buy
    :order-type :limit, :limit 50.0, :qty 1.0, :campaign "scalp-1", :label :open}
   {:date #inst "2026-06-01T20:10:11.740Z", :order-type :limit, :limit 50.0, :account/id 1
    :type :broker/order-confirmed, :order-id 103, :side :buy, :qty 1.0, :asset "SOLUSDT"}
   {:type :trader/cancel-order, :account/id 1, :order-id 103}
   {:type :broker/cancel-confirmed, :account/id 1, :order-id 103}
   {:order-id 103, :date #inst "2026-06-01T20:10:12.740Z", :type :broker/order-canceled}

   ;; portfolio-reallocation — no label, working
   {:type :trader/new-order, :account/id 2, :order-id 201, :asset "SPY", :side :buy
    :order-type :limit, :limit 500.0, :qty 10.0, :campaign "portfolio-reallocation"}
   {:date #inst "2026-06-01T20:10:17.741Z", :order-type :limit, :limit 500.0, :account/id 2
    :type :broker/order-confirmed, :order-id 201, :side :buy, :qty 10.0, :asset "SPY"}

   ;; portfolio-reallocation — no label, filled (closed, not in wo-dict)
   {:type :trader/new-order, :account/id 2, :order-id 202, :asset "QQQ", :side :sell
    :order-type :limit, :limit 400.0, :qty 5.0, :campaign "portfolio-reallocation"}
   {:date #inst "2026-06-01T20:10:24.741Z", :order-type :limit, :limit 400.0, :account/id 2
    :type :broker/order-confirmed, :order-id 202, :side :sell, :qty 5.0, :asset "QQQ"}
   {:type :broker/order-filled, :order-id 202, :fill-id "pr-f1", :date #inst "2026-06-01T20:10:37.742Z"
    :asset "QQQ", :qty 5.0, :side :sell, :price 400.0}

   ;; external campaign — working, must not appear in target campaign flows
   {:type :trader/new-order, :account/id 3, :order-id 301, :asset "XRPUSDT", :side :buy
    :order-type :limit, :limit 1.0, :qty 100.0, :campaign "external"}
   {:date #inst "2026-06-01T20:10:40.742Z", :order-type :limit, :limit 1.0, :account/id 3
    :type :broker/order-confirmed, :order-id 301, :side :buy, :qty 100.0, :asset "XRPUSDT"}
   {:type :broker/order-filled, :order-id 301, :fill-id "ext-f1", :date #inst "2026-06-01T20:10:50.742Z"
    :asset "XRPUSDT", :qty 100.0, :side :buy, :price 1.0}

   ;; no campaign — working, must not appear in target campaign flows
   {:type :trader/new-order, :account/id 3, :order-id 401, :asset "DOGEUSDT", :side :buy
    :order-type :limit, :limit 0.1, :qty 1000.0}
   {:date #inst "2026-06-01T20:10:45.742Z", :order-type :limit, :limit 0.1, :account/id 3
    :type :broker/order-confirmed, :order-id 401, :side :buy, :qty 1000.0, :asset "DOGEUSDT"}
   {:type :broker/order-filled, :order-id 401, :fill-id "no-camp-f1", :date #inst "2026-06-01T20:10:55.742Z"
    :asset "DOGEUSDT", :qty 1000.0, :side :buy, :price 0.1}])

(def channel-paper-flow (m/seed channel-paper-events))

(defn- tagged-messages [channel-flow]
  (m/? (m/reduce conj [] (campaign/campaign-tagged-combined-flow channel-flow))))

(defn- campaign-flows [channel-flow campaign-id]
  (let [tagged (campaign/campaign-tagged-combined-flow channel-flow)]
    (campaign/campaign-flows tagged campaign-id)))

(defn- final-campaign-dict [channel-flow campaign-id]
  (let [{:keys [working-order-dict-flow]} (campaign-flows channel-flow campaign-id)]
    (last (m/? (m/reduce conj [] working-order-dict-flow)))))

(defn- campaign-fills [channel-flow campaign-id]
  (let [{:keys [fill-flow]} (campaign-flows channel-flow campaign-id)]
    (m/? (m/reduce conj [] fill-flow))))

(def ^:private expected-order-tags
  "Campaign/label from each order's :trader/new-order; propagated to all later messages."
  {101 {:campaign "scalp-1" :label :open}
   102 {:campaign "scalp-1" :label :close}
   103 {:campaign "scalp-1" :label :open}
   201 {:campaign "portfolio-reallocation"}
   202 {:campaign "portfolio-reallocation"}
   301 {:campaign "external"}})

(defn- assert-tagged-message [msg expected]
  (let [oid (:order-id msg)
        typ (:type msg)]
    (is (some? (:campaign msg))
        (str "order " oid " " typ " should have :campaign"))
    (is (= (:campaign expected) (:campaign msg))
        (str "order " oid " " typ " wrong :campaign"))
    (if (contains? expected :label)
      (is (= (:label expected) (:label msg))
          (str "order " oid " " typ " wrong :label"))
      (is (nil? (:label msg))
          (str "order " oid " " typ " should not have :label")))))

(defn- assert-untagged-message [msg]
  (let [oid (:order-id msg)
        typ (:type msg)]
    (is (nil? (:campaign msg))
        (str "order " oid " " typ " should not have :campaign"))
    (is (nil? (:label msg))
        (str "order " oid " " typ " should not have :label"))))

(deftest campaign-tagged-combined-flow-propagates-campaign-and-label
  (let [msgs (tagged-messages channel-paper-flow)]
    (is (= (count channel-paper-events) (count msgs))
        "tagged flow emits one message per channel message")
    (doseq [msg msgs]
      (if-let [expected (get expected-order-tags (:order-id msg))]
        (assert-tagged-message msg expected)
        (assert-untagged-message msg)))))

(deftest scalp-1-wo-dict-only-contains-scalp-1-working-orders
  (let [dict (final-campaign-dict channel-paper-flow "scalp-1")
        orders (vals dict)]
    (is (= #{102} (set (keys dict))))
    (is (= 1 (count dict)))
    (is (every? #(= "scalp-1" (:order/campaign %)) orders))
    (is (not-any? #(contains? #{101 103 201 202 301 401} (:order/id %)) orders))
    (let [order-102 (get dict 102)]
      (is (= :working (:order/status order-102)))
      (is (= :close (:order/label order-102)))
      (is (= "ETHUSDT" (:order/asset order-102)))
      (is (== 0.002 (:order/qty-working order-102))))))

(deftest portfolio-reallocation-wo-dict-only-contains-its-working-orders
  (let [dict (final-campaign-dict channel-paper-flow "portfolio-reallocation")
        orders (vals dict)]
    (is (= #{201} (set (keys dict))))
    (is (= 1 (count dict)))
    (is (every? #(= "portfolio-reallocation" (:order/campaign %)) orders))
    (is (every? #(nil? (:order/label %)) orders))
    (is (not-any? #(contains? #{101 102 103 202 301 401} (:order/id %)) orders))
    (let [order-201 (get dict 201)]
      (is (= :working (:order/status order-201)))
      (is (= "SPY" (:order/asset order-201)))
      (is (== 10.0 (:order/qty-working order-201))))))

(deftest scalp-1-fill-flow-only-contains-scalp-1-fills
  (let [fills (campaign-fills channel-paper-flow "scalp-1")]
    (is (= 1 (count fills)))
    (let [fill (first fills)]
      (is (= "s1-o1" (:fill/id fill)))
      (is (= 101 (:fill/order-id fill)))
      (is (= "BTCUSDT" (:fill/asset fill)))
      (is (= :buy (:fill/side fill)))
      (is (== 0.001 (:fill/qty fill)))
      (is (== 100.0 (:fill/price fill)))
      (is (= #inst "2026-06-01T20:10:29.741Z" (:fill/date fill))))))

(deftest portfolio-reallocation-fill-flow-only-contains-its-fills
  (let [fills (campaign-fills channel-paper-flow "portfolio-reallocation")]
    (is (= 1 (count fills)))
    (let [fill (first fills)]
      (is (= "pr-f1" (:fill/id fill)))
      (is (= 202 (:fill/order-id fill)))
      (is (= "QQQ" (:fill/asset fill)))
      (is (= :sell (:fill/side fill)))
      (is (== 5.0 (:fill/qty fill)))
      (is (== 400.0 (:fill/price fill)))
      (is (= #inst "2026-06-01T20:10:37.742Z" (:fill/date fill))))))

(deftest campaign-flows-are-isolated-from-external-and-uncampaigned-orders
  (let [scalp-dict (final-campaign-dict channel-paper-flow "scalp-1")
        portfolio-dict (final-campaign-dict channel-paper-flow "portfolio-reallocation")
        scalp-fills (campaign-fills channel-paper-flow "scalp-1")
        portfolio-fills (campaign-fills channel-paper-flow "portfolio-reallocation")
        all-order-ids (set (concat (keys scalp-dict) (keys portfolio-dict)
                                   (map :fill/order-id scalp-fills)
                                   (map :fill/order-id portfolio-fills)))]
    (is (not (contains? all-order-ids 301)))
    (is (not (contains? all-order-ids 401)))
    (is (not= (set (keys scalp-dict)) (set (keys portfolio-dict))))
    (is (= #{101} (set (map :fill/order-id scalp-fills))))
    (is (= #{202} (set (map :fill/order-id portfolio-fills))))))
