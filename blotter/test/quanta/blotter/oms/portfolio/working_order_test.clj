(ns quanta.blotter.oms.portfolio.working-order-test
  (:require
   [clojure.test :refer :all]
   [quanta.blotter.oms.portfolio :as portfolio]
   [quanta.blotter.oms.portfolio.working-order :as wo]))

(def channel-paper-msgs
  [{:type :trader/new-order, :date #inst "2026-06-01T20:10:07.740Z", :account/id 1, :order-id 1, :asset "BTCUSDT", :side :buy, :order-type :limit, :limit 100.0, :qty 0.001}
   {:date #inst "2026-06-01T20:10:07.740Z", :order-type :limit, :limit 100.0, :account/id 1, :type :broker/order-confirmed, :order-id 1, :side :buy, :qty 0.001, :asset "BTCUSDT"}
   {:type :trader/new-order, :date #inst "2026-06-01T20:10:09.740Z", :account/id 2, :order-id 2, :asset "ETHUSDT", :side :sell, :order-type :limit, :limit 100.0, :qty 0.001}
   {:date #inst "2026-06-01T20:10:09.740Z", :order-type :limit, :limit 100.0, :account/id 2, :type :broker/order-confirmed, :order-id 2, :side :sell, :qty 0.001, :asset "ETHUSDT"}
   {:type :trader/cancel-order, :account/id 2, :order-id 2, :asset "ETHUSDT"}
   {:type :broker/cancel-confirmed, :account/id 2, :order-id 2}
   {:order-id 2, :date #inst "2026-06-01T20:10:12.740Z", :type :broker/order-canceled}
   {:type :trader/new-order, :date #inst "2026-06-01T20:10:17.741Z", :account/id 2, :order-id 3, :asset "ETHUSDT", :side :sell, :order-type :limit, :limit 100.0, :qty 0.001}
   {:date #inst "2026-06-01T20:10:17.741Z", :order-type :limit, :limit 100.0, :account/id 2, :type :broker/order-confirmed, :order-id 3, :side :sell, :qty 0.001, :asset "ETHUSDT"}
   {:type :trader/new-order, :date #inst "2026-06-01T20:10:24.741Z", :account/id 2, :order-id 4, :asset "ETHUSDT", :side :sell, :order-type :limit, :limit 100.0, :qty 0.001}
   {:date #inst "2026-06-01T20:10:24.741Z", :order-type :limit, :limit 100.0, :account/id 2, :type :broker/order-confirmed, :order-id 4, :side :sell, :qty 0.001, :asset "ETHUSDT"}
   {:type :broker/order-filled, :order-id 4, :fill-id "m-9By0", :date #inst "2026-06-01T20:10:29.741Z", :asset "ETHUSDT", :qty 0.001, :side :sell, :price 100.0}
   {:type :broker/order-filled, :order-id 3, :fill-id "7N-G_C", :date #inst "2026-06-01T20:10:37.742Z", :asset "ETHUSDT", :qty 0.001, :side :sell, :price 101.0}
   {:type :broker/order-filled, :order-id 1, :fill-id "KKEY9v", :date #inst "2026-06-01T20:10:52.742Z", :asset "BTCUSDT", :qty 0.001, :side :buy, :price 10000.0}])

(defn- collect [msgs]
  (second
   (reduce
    (fn [[state outs] msg]
      (let [{:keys [state out-msg]} (portfolio/process-message state msg)]
        [state (cond-> outs
                 (:order-change out-msg)
                 (conj (:order-change out-msg)))]))
    [(portfolio/empty-state) []]
    msgs)))

(defn- final-for-order [emissions order-id]
  (->> emissions (filter #(= order-id (:order/id %))) last))

(defn- chronological-for-order [emissions order-id]
  (filter #(= order-id (:order/id %)) emissions))

(deftest order-change-emits-flat-maps
  (let [emissions (collect channel-paper-msgs)]
    (is (every? map? emissions))
    (is (every? #(contains? % :order/id) emissions))
    (is (every? #(instance? java.util.Date (:order/date %)) emissions))))

(deftest order-date-from-new-order-message
  (let [msgs [{:type :trader/new-order :date #inst "2026-06-01T12:00:00.000Z"
               :account/id 1 :order-id 9 :asset "BTC" :side :buy :order-type :limit :qty 0.001M :limit 100M}
              {:type :broker/order-confirmed :account/id 1 :order-id 9 :asset "BTC"
               :side :buy :order-type :limit :qty 0.001M :limit 100M :date #inst "2026-06-01T12:00:00.000Z"}]
        state (reduce (fn [st msg] (:state (portfolio/process-message st msg)))
                      (portfolio/empty-state) msgs)
        order (get-in state [:working-order 9])]
    (is (= #inst "2026-06-01T12:00:00.000Z" (:order/date order)))
    (is (instance? java.util.Date (:order/date order)))))

(deftest incremental-emissions-per-order
  (let [by-id (group-by :order/id (collect channel-paper-msgs))]
    (is (> (count (get by-id 1)) 1))
    (is (> (count (get by-id 2)) 1))
    (is (> (count (get by-id 4)) 1))))

(deftest order-2-cancelled
  (let [last-2 (final-for-order (collect channel-paper-msgs) 2)]
    (is (= :cancelled (:order/status last-2)))
    (is (== 0.0 (:order/qty-working last-2)))
    (is (== 0.0 (:order/qty-filled last-2)))
    (is (nil? (:order/avg-price last-2)))
    (is (some #(= :broker/order-canceled (:type %)) (:order/history last-2)))
    (is (= #inst "2026-06-01T20:10:09.740Z" (:order/date last-2)))))

(deftest order-4-filled
  (let [last-4 (final-for-order (collect channel-paper-msgs) 4)]
    (is (= :filled (:order/status last-4)))
    (is (== 0.001 (:order/qty-filled last-4)))
    (is (== 0.0 (:order/qty-working last-4)))
    (is (== 100.0 (:order/avg-price last-4)))
    (is (== 100.0 (:order/limit last-4)))))

(deftest limit-order-has-limit-in-view
  (let [emissions (collect channel-paper-msgs)]
    (is (== 100.0 (:order/limit (final-for-order emissions 1))))
    (is (== 100.0 (:order/limit (final-for-order emissions 2))))))

(deftest market-order-has-no-limit-in-view
  (let [order (final-for-order
               (collect [{:type :trader/new-order :date #inst "2026-06-01T12:00:00.000Z"
                          :account/id 1 :order-id 1 :asset "X" :side :buy :order-type :market :qty 1.0}])
               1)]
    (is (nil? (:order/limit order)))))

(deftest modify-updates-limit-in-view
  (let [order (final-for-order
               (collect [{:type :trader/new-order :date #inst "2026-06-01T12:00:00.000Z"
                          :account/id 1 :order-id 1 :asset "X" :side :buy
                          :order-type :limit :qty 1.0M :limit 100M}
                         {:type :broker/order-modified :account/id 1 :order-id 1 :limit 110M}])
               1)]
    (is (== 110M (:order/limit order)))))

(deftest avg-price-nil-before-fill
  (let [first-4 (first (chronological-for-order (collect channel-paper-msgs) 4))]
    (is (nil? (:order/avg-price first-4)))))

(deftest avg-price-keeps-max-current-and-last-fill-scale
  (let [emissions (collect [{:type :trader/new-order
                             :date #inst "2026-06-01T12:00:00.000Z"
                             :account/id 1 :order-id 1 :asset "X"
                             :side :buy :order-type :market :qty 2M}
                            {:type :broker/order-filled :account/id 1 :order-id 1
                             :fill-id "f1" :asset "X" :side :buy :qty 1M
                             :price 10.1234M}
                            {:type :broker/order-filled :account/id 1 :order-id 1
                             :fill-id "f2" :asset "X" :side :buy :qty 1M
                             :price 10.1M}])
        avg (:order/avg-price (last emissions))]
    (is (= 10.1117M avg))
    (is (= 4 (.scale ^BigDecimal avg)))))

(deftest hydrate-order-preserves-avg-price
  (let [hydrated (wo/hydrate-order
                  {:order/id 1
                   :order/qty 2M
                   :order/qty-filled 1M
                   :order/avg-price 10.1234M
                   :order/history []})]
    (is (= 10.1234M (:order/avg-price hydrated)))
    (is (= 4 (.scale ^BigDecimal (:order/avg-price hydrated))))))

(deftest order-1-working-until-final-fill
  (let [order-1 (chronological-for-order (collect channel-paper-msgs) 1)]
    (is (every? #(= :working (:order/status %)) (butlast order-1)))
    (is (= :filled (:order/status (last order-1))))
    (is (== 10000.0 (:order/avg-price (last order-1))))))

(deftest working-order-dict-keeps-open-orders-only
  (let [state (reduce (fn [st msg] (:state (portfolio/process-message st msg)))
                      (portfolio/empty-state)
                      [{:type :trader/new-order, :date #inst "2026-06-01T12:00:00.000Z", :account/id 1, :order-id 9
                        :asset "BTCUSDT", :side :buy, :order-type :market, :qty 0.001}])]
    (is (= 1 (count (:working-order state))))
    (is (= 9 (:order/id (get-in state [:working-order 9]))))
    (is (= :market (:order/type (get-in state [:working-order 9]))))))

(deftest rejected-order-has-text
  (let [order (final-for-order
               (collect [{:type :trader/new-order :date #inst "2026-06-01T12:00:00.000Z"
                          :account/id 1 :order-id 1 :asset "X" :side :buy :order-type :market :qty 1.0}
                         {:type :broker/order-rejected :account/id 1 :order-id 1 :message "market-closed"}])
               1)]
    (is (= :rejected (:order/status order)))
    (is (= "market-closed" (:order/text order)))
    (is (= :market (:order/type order)))))

(deftest campaign-and-label-in-order-view
  (let [order (final-for-order
               (collect [{:type :trader/new-order :date #inst "2026-06-01T12:00:00.000Z"
                          :account/id 1 :order-id 1 :asset "X"
                          :side :buy :order-type :market :qty 1.0M
                          :campaign "fx-q2" :label :hedge}])
               1)]
    (is (= "fx-q2" (:order/campaign order)))
    (is (= :hedge (:order/label order)))))

(def duplicate-cancel-msgs
  [{:type :trader/new-order, :date #inst "2026-06-26T22:43:50.268Z", :account/id 2000, :order-id "OCCXCB9t", :asset "BTCUSDT.S.BB",
    :side :buy, :order-type :limit, :limit 58900.0M, :qty 0.001M}
   {:order-type :limit, :date #inst "2026-06-26T22:43:50.268Z", :limit 58900.0M,
    :account/id 2000, :type :broker/order-confirmed, :order-id "OCCXCB9t", :side :buy,
    :qty 0.001M, :asset "BTCUSDT.S.BB", :message ""}
   {:order-type :limit, :date #inst "2026-06-26T22:43:50.112Z", :limit 58900.0M,
    :account/id 2000, :type :broker/order-confirmed, :order-id "OCCXCB9t", :side :buy,
    :qty 0.001000M, :asset "BTCUSDT.S.BB", :message ""}
   {:type :trader/modify-order, :account/id 2000, :order-id "OCCXCB9t",
    :asset "BTCUSDT.S.BB", :limit 58901.0M}
   {:order-type :limit, :date #inst "2026-06-26T22:43:58.118Z", :limit 58901.0M,
    :account/id 2000, :type :broker/order-confirmed, :order-id "OCCXCB9t", :side :buy,
    :qty 0.001000M, :asset "BTCUSDT.S.BB", :message ""}
   {:order-id "OCCXCB9t", :asset "BTCUSDT.S.BB", :limit 58901.0M, :account/id 2000,
    :type :broker/order-modified, :message "modify accepted"}
   {:type :trader/cancel-order, :account/id 2000, :order-id "OCCXCB9t", :asset "BTCUSDT.S.BB"}
   {:type :broker/order-canceled, :account/id 2000, :order-id "OCCXCB9t",
    :date #inst "2026-06-26T22:44:06.114Z"}
   {:type :broker/cancel-confirmed, :account/id 2000, :order-id "OCCXCB9t",
    :message "cancel accepted"}])

(deftest cancel-emits-once-despite-duplicate-confirms-and-cancel-ack
  (let [emissions (collect duplicate-cancel-msgs)
        closed (filter wo/order-done? emissions)
        cancelled (filter #(= :cancelled (:order/status %)) closed)]
    (is (= 1 (count cancelled))
        "order-canceled + cancel-confirmed must not emit two finished views")
    (is (= "OCCXCB9t" (:order/id (first cancelled))))
    (is (== 58901.0M (:order/limit (first cancelled))))
    (is (some #(= :broker/order-canceled (:type %)) (:order/history (first cancelled))))))

(deftest late-msg-after-close-emits-update-for-unknown
  (let [msgs [{:type :trader/new-order :account/id 1 :order-id 1 :asset "X"
               :side :buy :order-type :market :qty 1.0M
               :date #inst "2026-06-01T11:00:00.000Z"}
              {:type :broker/order-filled :account/id 1 :order-id 1 :fill-id "f1"
               :asset "X" :side :buy :qty 1.0M :price 10.0M
               :date #inst "2026-06-01T11:30:00.000Z"}
              {:type :broker/order-canceled :account/id 1 :order-id 1
               :date #inst "2026-06-01T12:00:00.000Z"}]
        [state outs] (reduce
                      (fn [[st outs] msg]
                        (let [{:keys [state out-msg]} (portfolio/process-message st msg)]
                          [state (conj outs out-msg)]))
                      [(portfolio/empty-state) []]
                      msgs)
        late (last outs)]
    (println "late: " late)
    (is (empty? (:working-order state)))
    (is (some? (:order-closed (second outs))))
    (is (some? (:trade (second outs))))
    (is (= :broker/order-canceled (:type (:update-for-unknown late))))
    (is (= 1 (:order-id (:update-for-unknown late))))
    (is (nil? (:order-change late)))
    (is (nil? (get-in state [:working-order 1])))))

(deftest fill-for-unknown-order-does-not-emit-trade
  (let [msg {:type :broker/order-filled
             :date #inst "2026-06-01T12:00:00.000Z"
             :account/id 1 :order-id 99 :fill-id "unknown-fill"
             :asset "X" :side :buy :qty 1M :price 10M}
        {:keys [state out-msg]} (portfolio/process-message
                                 (portfolio/empty-state)
                                 msg)]
    (is (= msg (:update-for-unknown out-msg)))
    (is (nil? (:trade out-msg)))
    (is (empty? (:open-position state)))))
