(ns quanta.blotter.paper.orderfiller-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [quanta.blotter.paper.orderfiller :as of]))

(def order
  {:order-id 1
   :account/id 3
   :asset "BTCUSDT"
   :side :buy
   :limit 100.0M
   :qty 0.001M})

(defn- collect
  "Runs the fill flow to completion and returns all emitted messages."
  [settings]
  (m/? (m/reduce conj [] (of/random-fill-flow settings (fn [_]) order))))

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

(deftest guaranteed-single-fill
  (let [emissions (collect {:fill-probability 100 :wait-seconds 0 :fill-qty-prct [100]})
        fills (filter #(= :broker/order-filled (:type %)) emissions)]
    (is (= 1 (count fills)))
    (is (= 0.001M (:qty (first fills))))
    (is (= :buy (:side (first fills))))
    (is (= 100.0M (:price (first fills))))
    (is (= 3 (:account/id (first fills))))))

(deftest guaranteed-partial-fills
  (let [emissions (collect {:fill-probability 100 :wait-seconds 0 :fill-qty-prct [50 25 25]})
        fills (filter #(= :broker/order-filled (:type %)) emissions)]
    (is (= 3 (count fills)) "one fill per slice")
    (is (= [0.0005M 0.00025M 0.00025M] (mapv :qty fills)))
    (is (= 0.001M (reduce + (map :qty fills))) "total filled equals order qty")
    (is (apply distinct? (map :fill-id fills)) "each fill has a unique id")))

(deftest cancel-while-waiting-emits-order-canceled
  (testing "an unfilled order that is disposed emits :broker/order-canceled"
    (let [seen (atom [])
          flow (of/random-fill-flow {:fill-probability 0 :wait-seconds 60 :fill-qty-prct [100]}
                                    (fn [_]) order)
          task (m/reduce (fn [_ v] (swap! seen conj v) nil) nil flow)
          dispose (task (fn [_]) (fn [_]))]
      ;; the flow is parked in the wait; cancelling triggers the Cancelled branch
      (Thread/sleep 50)
      (dispose)
      (Thread/sleep 50)
      (is (some #(= :broker/order-canceled (:type %)) @seen))
      (is (not-any? #(= :broker/order-filled (:type %)) @seen)))))
