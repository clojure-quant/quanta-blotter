(ns quanta.blotter.oms.trading-state-trade-test
  (:require
   [clojure.test :refer :all]
   [missionary.core :as m]
   [quanta.missionary.time-flow :refer [create-time-flow]]
   [quanta.blotter.oms.flow.campaign :as campaign]
   [quanta.blotter.oms.flow.trading-state :as trading-state]))

(defn- new-order [oid asset side qty campaign]
  {:type :trader/new-order
   :account/id 1
   :order-id oid
   :asset asset
   :side side
   :order-type :limit
   :limit 100.0
   :qty qty
   :campaign campaign})

(defn- confirmed [oid asset side qty]
  {:type :broker/order-confirmed
   :account/id 1
   :order-id oid
   :asset asset
   :side side
   :order-type :limit
   :limit 100.0
   :qty qty
   :date #inst "2026-06-01T20:10:00.000Z"})

(defn- filled [oid asset side fill-qty fill-id]
  {:type :broker/order-filled
   :account/id 1
   :order-id oid
   :fill-id fill-id
   :asset asset
   :qty fill-qty
   :side side
   :price 100.0
   :date #inst "2026-06-01T20:10:00.000Z"})

(defn- complete-set [oid asset side qty campaign]
  (let [half (/ qty 2.0)]
    [(new-order oid asset side qty campaign)
     (confirmed oid asset side qty)
     (filled oid asset side half (str "f-" oid "-a"))
     (filled oid asset side half (str "f-" oid "-b"))]))

(defn- overlapping-set [oid-a oid-b asset side qty campaign]
  (let [half (/ qty 2.0)]
    [(new-order oid-a asset side qty campaign)
     (new-order oid-b asset side qty campaign)
     (confirmed oid-a asset side qty)
     (confirmed oid-b asset side qty)
     (filled oid-a asset side half (str "f-" oid-a "-a"))
     (filled oid-b asset side half (str "f-" oid-b "-a"))
     (filled oid-a asset side half (str "f-" oid-a "-b"))
     (filled oid-b asset side half (str "f-" oid-b "-b"))]))

(defn- with-delays [msgs]
  (into [] (mapcat (fn [msg] [1 msg]) msgs)))

(def ^:private orders-per-round 18)

(defn- channel-paper-msgs
  "One round of open+close for 3 assets. Order-ids are `base` .. `base+17`."
  [base]
  (let [o #(+ base %)]
    (concat
     ;; campaign "a": BTCUSDT + ETHUSDT
     (complete-set (o 0) "BTCUSDT" :buy 1.0 "a")
     (complete-set (o 1) "BTCUSDT" :buy 1.0 "a")
     (overlapping-set (o 2) (o 3) "BTCUSDT" :buy 1.0 "a")
     (complete-set (o 4) "ETHUSDT" :buy 1.0 "a")
     (complete-set (o 5) "ETHUSDT" :buy 1.0 "a")
     (overlapping-set (o 6) (o 7) "ETHUSDT" :buy 1.0 "a")
     ;; campaign "b": SOLUSDT
     (complete-set (o 8) "SOLUSDT" :buy 1.0 "b")
     (complete-set (o 9) "SOLUSDT" :buy 1.0 "b")
     (overlapping-set (o 10) (o 11) "SOLUSDT" :buy 1.0 "b")
     ;; close longs (qty-open 4.0 → two sells of 2.0)
     (overlapping-set (o 12) (o 13) "BTCUSDT" :sell 2.0 "a")
     (overlapping-set (o 14) (o 15) "ETHUSDT" :sell 2.0 "a")
     (overlapping-set (o 16) (o 17) "SOLUSDT" :sell 2.0 "b"))))

(defn- channel-paper-time-flow*
  ([rounds]
   (create-time-flow
    (with-delays
     (mapcat (fn [i] (channel-paper-msgs (inc (* i orders-per-round))))
             (range rounds))))))

(def channel-paper-time-flow (channel-paper-time-flow* 1))

(defn- run-flows! [time-flow]
  (let [channel-flow (m/stream time-flow)
        tagged (m/stream (campaign/campaign-tagged-combined-flow channel-flow))
        {:keys [fill-flow position-change-flow
                open-position-dict-flow working-order-dict-flow]}
        (trading-state/create-trading-state! tagged)
        camp-a (campaign/campaign-flows tagged "a")
        camp-b (campaign/campaign-flows tagged "b")]
    (m/? (m/join vector
                 (m/reduce conj [] fill-flow)
                 (m/reduce conj [] position-change-flow)
                 (m/reduce conj [] open-position-dict-flow)
                 (m/reduce conj [] working-order-dict-flow)
                 (m/reduce conj [] (:fill-flow camp-a))
                 (m/reduce conj [] (:open-position-dict-flow camp-a))
                 (m/reduce conj [] (:working-order-dict-flow camp-a))
                 (m/reduce conj [] (:fill-flow camp-b))
                 (m/reduce conj [] (:open-position-dict-flow camp-b))
                 (m/reduce conj [] (:working-order-dict-flow camp-b))))))

(defn- assert-round-results [rounds fills open-dicts wo-dicts
                             fills-a open-dicts-a wo-dicts-a
                             fills-b open-dicts-b wo-dicts-b]
  (let [fills-by-asset (group-by :fill/asset fills)
        final-open (last open-dicts)
        final-wo (last wo-dicts)]
    ;; 18 orders × 2 fills × rounds
    (is (= (* 36 rounds) (count fills)))
    (doseq [asset ["BTCUSDT" "ETHUSDT" "SOLUSDT"]]
      (let [asset-fills (get fills-by-asset asset)
            buys (filter #(= :buy (:fill/side %)) asset-fills)
            sells (filter #(= :sell (:fill/side %)) asset-fills)]
        (is (= (* 12 rounds) (count asset-fills))
            (str asset " should have " (* 12 rounds) " fills"))
        (is (= (* 8 rounds) (count buys)))
        (is (= (* 4 rounds) (count sells)))
        (is (every? #(= 1 (:fill/account-id %)) asset-fills))
        (is (every? #(== 0.5M (:fill/qty %)) buys))
        (is (every? #(== 1.0M (:fill/qty %)) sells))))

    (is (= {} final-wo)
        "all orders fully filled → no working orders")
    (is (= {} final-open)
        "all positions closed after overlapping sell sets")

    ;; campaign "a": BTCUSDT + ETHUSDT = 2 × 12 fills × rounds
    (is (= (* 24 rounds) (count fills-a))
        (str "campaign a fill-flow should have " (* 24 rounds) " fills"))
    (is (every? #(= "a" (:fill/campaign %)) fills-a))
    (is (= #{"BTCUSDT" "ETHUSDT"} (set (map :fill/asset fills-a))))
    (is (= {} (last wo-dicts-a))
        "campaign a working-order dict empty")
    (is (= {} (last open-dicts-a))
        "campaign a open-position dict empty")

    ;; campaign "b": SOLUSDT = 12 fills × rounds
    (is (= (* 12 rounds) (count fills-b))
        (str "campaign b fill-flow should have " (* 12 rounds) " fills"))
    (is (every? #(= "b" (:fill/campaign %)) fills-b))
    (is (= #{"SOLUSDT"} (set (map :fill/asset fills-b))))
    (is (= {} (last wo-dicts-b))
        "campaign b working-order dict empty")
    (is (= {} (last open-dicts-b))
        "campaign b open-position dict empty")))

(deftest all-fills-positions-and-empty-working-orders
  (let [[fills _pos-changes open-dicts wo-dicts
         fills-a open-dicts-a wo-dicts-a
         fills-b open-dicts-b wo-dicts-b] (run-flows! channel-paper-time-flow)]
    (assert-round-results 1 fills open-dicts wo-dicts
                          fills-a open-dicts-a wo-dicts-a
                          fills-b open-dicts-b wo-dicts-b)))

(deftest five-rounds-same-flow-distinct-order-ids
  (let [time-flow (channel-paper-time-flow* 5)
        [fills _pos-changes open-dicts wo-dicts
         fills-a open-dicts-a wo-dicts-a
         fills-b open-dicts-b wo-dicts-b] (run-flows! time-flow)
        order-ids (set (map :fill/order-id fills))]
    (assert-round-results 5 fills open-dicts wo-dicts
                          fills-a open-dicts-a wo-dicts-a
                          fills-b open-dicts-b wo-dicts-b)
    (is (= (* orders-per-round 5) (count order-ids))
        "each of the 5 rounds uses distinct order-ids")
    (is (= (set (range 1 (inc (* orders-per-round 5)))) order-ids))))
