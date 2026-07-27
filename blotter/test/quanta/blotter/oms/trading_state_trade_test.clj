(ns quanta.blotter.oms.trading-state-trade-test
  (:require
   [clojure.test :refer :all]
   [missionary.core :as m]
   [quanta.blotter.oms.flow.campaign :as campaign]
   [quanta.blotter.oms.portfolio :as portfolio]))

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

(defn- channel-paper-msgs*
  ([rounds]
   (mapcat (fn [i] (channel-paper-msgs (inc (* i orders-per-round))))
           (range rounds))))

(defn- fold-all [msgs]
  (let [tagged (m/? (m/reduce conj [] (campaign/campaign-tagged-combined-flow (m/seed msgs))))]
    (reduce
     (fn [[state outs] msg]
       (let [{:keys [state out-msg]} (portfolio/process-message state msg)]
         [state (conj outs out-msg)]))
     [(portfolio/empty-state) []]
     tagged)))

(defn- fold-campaign [msgs campaign-id]
  (let [tagged (m/? (m/reduce conj [] (campaign/campaign-tagged-combined-flow (m/seed msgs))))
        filtered (filter #(= (:campaign %) campaign-id) tagged)]
    (reduce
     (fn [[state outs] msg]
       (let [{:keys [state out-msg]} (portfolio/process-message state msg)]
         [state (conj outs out-msg)]))
     [(portfolio/empty-state) []]
     filtered)))

(defn- run-folds! [msgs]
  (let [[state outs] (fold-all msgs)
        [state-a outs-a] (fold-campaign msgs "a")
        [state-b outs-b] (fold-campaign msgs "b")]
    {:fills (into [] (keep :trade) outs)
     :open-dicts [(:open-position state)]
     :wo-dicts [(:working-order state)]
     :fills-a (into [] (keep :trade) outs-a)
     :open-dicts-a [(:open-position state-a)]
     :wo-dicts-a [(:working-order state-a)]
     :fills-b (into [] (keep :trade) outs-b)
     :open-dicts-b [(:open-position state-b)]
     :wo-dicts-b [(:working-order state-b)]}))

(defn- assert-round-results [rounds {:keys [fills open-dicts wo-dicts
                                            fills-a open-dicts-a wo-dicts-a
                                            fills-b open-dicts-b wo-dicts-b]}]
  (let [fills-by-asset (group-by :fill/asset fills)
        final-open (last open-dicts)
        final-wo (last wo-dicts)]
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

    (is (= (* 24 rounds) (count fills-a))
        (str "campaign a fill-flow should have " (* 24 rounds) " fills"))
    (is (every? #(= "a" (:fill/campaign %)) fills-a))
    (is (= #{"BTCUSDT" "ETHUSDT"} (set (map :fill/asset fills-a))))
    (is (= {} (last wo-dicts-a))
        "campaign a working-order dict empty")
    (is (= {} (last open-dicts-a))
        "campaign a open-position dict empty")

    (is (= (* 12 rounds) (count fills-b))
        (str "campaign b fill-flow should have " (* 12 rounds) " fills"))
    (is (every? #(= "b" (:fill/campaign %)) fills-b))
    (is (= #{"SOLUSDT"} (set (map :fill/asset fills-b))))
    (is (= {} (last wo-dicts-b))
        "campaign b working-order dict empty")
    (is (= {} (last open-dicts-b))
        "campaign b open-position dict empty")))

(deftest all-fills-positions-and-empty-working-orders
  (assert-round-results 1 (run-folds! (channel-paper-msgs* 1))))

(deftest multi-round-all-fills-positions-and-empty-working-orders
  (assert-round-results 3 (run-folds! (channel-paper-msgs* 3))))
