(ns quanta.market-sim.broker-paper.orderfiller-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [tick.core :as t]
   [quanta.market-sim.broker-paper.orderfiller :as of]
   [quanta.quote.core :as qc]))

(def limit-order
  {:order-id 1
   :account/id 3
   :asset "BTCUSDT"
   :side :buy
   :order-type :limit
   :limit 100.0M
   :qty 0.001M})

(def market-order
  {:order-id 2
   :account/id 3
   :asset "BTCUSDT"
   :side :buy
   :order-type :market
   :qty 0.001M})

(def stop-buy-order
  {:order-id 3
   :account/id 3
   :asset "BTCUSDT"
   :side :buy
   :order-type :stop
   :limit 100.0M
   :qty 0.001M})

(def ctx {:quote-manager ::test-qm})

(defn- q
  ([bid] (q bid (t/instant)))
  ([bid ts]
   {:asset "BTCUSDT" :bid bid :ask (+ bid 0.01M) :ts ts}))

(defn- quotes-flow
  "Finite flow that emits each quote once."
  [quotes]
  (m/seed quotes))

(defn- collect
  "Runs the fill flow to completion against a fixed quote sequence."
  [settings quotes & {:keys [order] :or {order limit-order}}]
  (with-redefs [qc/asset-quote-flow (fn [_ _] (quotes-flow quotes))]
    (m/? (m/reduce conj [] (of/simulated-fill-flow ctx settings (fn [_]) order)))))

(deftest fill-slices-single
  (is (= [0.001M] (of/fill-slices [100] 0.001M)))
  (is (= [0.001M] (of/fill-slices nil 0.001M))
      "missing fill-qty-prct defaults to a single full fill"))

(deftest fill-slices-multi
  (let [slices (of/fill-slices [50 25 25] 0.001M)]
    (is (= 3 (count slices)))
    (is (= 0.001M (reduce + slices)) "slices sum exactly to order qty")
    (is (= [0.0005M 0.00025M 0.00025M] slices))))

(deftest fill-slices-uneven-remainder
  (let [slices (of/fill-slices [33 33 34] 0.001M)]
    (is (= 3 (count slices)))
    (is (= 0.001M (reduce + slices))
        "last slice absorbs rounding so total is exact")))

(deftest order-executable-limit
  (is (of/order-executable? :limit :buy 100.0M 99.0M))
  (is (of/order-executable? :limit :buy 100.0M 100.0M))
  (is (not (of/order-executable? :limit :buy 100.0M 101.0M)))
  (is (of/order-executable? :limit :sell 100.0M 101.0M))
  (is (of/order-executable? :limit :sell 100.0M 100.0M))
  (is (not (of/order-executable? :limit :sell 100.0M 99.0M))))

(deftest stop-triggered
  (is (of/stop-triggered? :buy 100.0M 101.0M))
  (is (not (of/stop-triggered? :buy 100.0M 100.0M)))
  (is (of/stop-triggered? :sell 100.0M 99.0M))
  (is (not (of/stop-triggered? :sell 100.0M 100.0M))))

(deftest market-fills-at-bid
  (let [emissions (collect {:fill-probability 100 :ms-between-fills 0 :fill-qty-prct [100]}
                           [(q 95.5M)]
                           :order market-order)
        fills (filter #(= :broker/order-filled (:type %)) emissions)]
    (is (= 1 (count fills)))
    (is (= 0.001M (:qty (first fills))))
    (is (= 95.5M (:price (first fills))))
    (is (= 3 (:account/id (first fills))))))

(deftest limit-buy-fills-when-bid-at-or-below-limit
  (let [emissions (collect {:fill-probability 100 :ms-between-fills 0 :fill-qty-prct [100]}
                           [(q 101.0M) (q 100.0M)])
        fills (filter #(= :broker/order-filled (:type %)) emissions)]
    (is (= 1 (count fills)))
    (is (= 100.0M (:price (first fills))))))

(deftest limit-sell-fills-when-bid-at-or-above-limit
  (let [sell (assoc limit-order :side :sell :limit 100.0M)
        emissions (collect {:fill-probability 100 :ms-between-fills 0 :fill-qty-prct [100]}
                           [(q 99.0M) (q 100.0M)]
                           :order sell)
        fills (filter #(= :broker/order-filled (:type %)) emissions)]
    (is (= 1 (count fills)))
    (is (= 100.0M (:price (first fills))))))

(deftest stop-buy-triggers-and-fills-at-bid
  (let [emissions (collect {:fill-probability 100 :ms-between-fills 0 :fill-qty-prct [100]}
                           [(q 99.0M) (q 100.5M)]
                           :order stop-buy-order)
        fills (filter #(= :broker/order-filled (:type %)) emissions)]
    (is (= 1 (count fills)))
    (is (= 100.5M (:price (first fills))))))

(deftest guaranteed-partial-fills
  (let [t0 (t/instant)
        t1 (t/>> t0 (t/new-duration 1 :seconds))
        t2 (t/>> t0 (t/new-duration 2 :seconds))
        emissions (collect {:fill-probability 100 :ms-between-fills 0 :fill-qty-prct [50 25 25]}
                           [(q 90.0M t0) (q 91.0M t1) (q 92.0M t2)]
                           :order market-order)
        fills (filter #(= :broker/order-filled (:type %)) emissions)]
    (is (= 3 (count fills)) "one fill per slice")
    (is (= [0.0005M 0.00025M 0.00025M] (mapv :qty fills)))
    (is (= [90.0M 91.0M 92.0M] (mapv :price fills)))
    (is (= 0.001M (reduce + (map :qty fills))) "total filled equals order qty")
    (is (apply distinct? (map :fill-id fills)) "each fill has a unique id")))

(deftest ms-between-fills-blocks-until-ts-elapsed
  (let [t0 (t/instant)
        t1 (t/>> t0 (t/new-duration 1 :seconds))
        t5 (t/>> t0 (t/new-duration 5 :seconds))
        emissions (collect {:fill-probability 100 :ms-between-fills 5000 :fill-qty-prct [50 50]}
                           [(q 90.0M t0) (q 91.0M t1) (q 92.0M t5)]
                           :order market-order)
        fills (filter #(= :broker/order-filled (:type %)) emissions)]
    (is (= 2 (count fills)))
    (is (= [90.0M 92.0M] (mapv :price fills))
        "second slice waits until quote ts is >= ms-between-fills after first fill")))

(deftest fill-probability-zero-never-fills
  (let [emissions (collect {:fill-probability 0 :ms-between-fills 0 :fill-qty-prct [100]}
                           [(q 90.0M) (q 91.0M) (q 92.0M)]
                           :order market-order)]
    (is (empty? (filter #(= :broker/order-filled (:type %)) emissions)))))

(deftest cancel-while-waiting-emits-order-canceled
  (testing "an unfilled order that is disposed emits :broker/order-canceled"
    (let [seen (atom [])
          ;; parks forever until cancelled
          hanging (m/observe (fn [_] (fn [])))
          flow (with-redefs [qc/asset-quote-flow (fn [_ _] hanging)]
                 (of/simulated-fill-flow ctx
                                         {:fill-probability 100 :ms-between-fills 60 :fill-qty-prct [100]}
                                         (fn [_])
                                         limit-order))
          task (m/reduce (fn [_ v] (swap! seen conj v) nil) nil flow)
          dispose (task (fn [_]) (fn [_]))]
      (Thread/sleep 50)
      (dispose)
      (Thread/sleep 50)
      (is (some #(= :broker/order-canceled (:type %)) @seen))
      (is (not-any? #(= :broker/order-filled (:type %)) @seen)))))
