(ns quanta.blotter.open-positions-test
  (:require
   [clojure.test :refer :all]
   [missionary.core :as m]
   [quanta.blotter.open-positions :as op]))

(defn- fill [account asset side qty price]
  {:type :broker/order-filled
   :account/id account
   :asset asset
   :side side
   :qty qty
   :price price})

(defn- emissions [fills-or-flow & [opts]]
  (let [flow (if (sequential? fills-or-flow)
                (m/seed fills-or-flow)
                fills-or-flow)]
    (m/? (m/reduce conj [] (op/position-change-flow flow opts)))))

(defn- last-emission [fills & [opts]]
  (last (emissions fills opts)))

(deftest buy-sell-flip-average
  (let [fills [(fill 1 "X" :buy 100.0 10.0)
               (fill 1 "X" :sell 110.0 11.0)]
        ems (emissions fills {:method :average})]
    (is (= 2 (count ems)))
    (is (= :long (:position/side (nth ems 0))))
    (is (= 100.0 (:position/qty (nth ems 0))))
    (is (= 10.0 (:position/average-entry-price (nth ems 0))))
    (is (= 0.0 (:position/realized-pl (nth ems 0))))
    (is (= :short (:position/side (nth ems 1))))
    (is (= 10.0 (:position/qty (nth ems 1))))
    (is (= 11.0 (:position/average-entry-price (nth ems 1))))
    (is (= 100.0 (:position/realized-pl (nth ems 1))))))

(deftest buy-sell-flip-fifo-same-lots
  (let [fills [(fill 1 "X" :buy 100.0 10.0)
               (fill 1 "X" :sell 110.0 11.0)]
        ems (emissions fills {:method :fifo})
        last-pos (last ems)]
    (is (= 10.0 (:position/average-entry-price (first ems))))
    (is (= :short (:position/side last-pos)))
    (is (= 11.0 (:position/average-entry-price last-pos)))
    (is (= 100.0 (:position/realized-pl last-pos)))))

(deftest fifo-consumes-oldest-lot-first
  (let [fills [(fill 1 "X" :buy 50.0 10.0)
               (fill 1 "X" :buy 50.0 12.0)
               (fill 1 "X" :sell 60.0 15.0)]
        pos (last-emission fills {:method :fifo})]
    (is (= :long (:position/side pos)))
    (is (= 40.0 (:position/qty pos)))
    (is (= 12.0 (:position/average-entry-price pos)))
    (is (= 280.0 (:position/realized-pl pos)))))

(deftest average-partial-close-keeps-avg
  (let [fills [(fill 1 "X" :buy 100.0 10.0)
               (fill 1 "X" :sell 40.0 12.0)]
        pos (last-emission fills {:method :average})]
    (is (= :long (:position/side pos)))
    (is (= 60.0 (:position/qty pos)))
    (is (= 10.0 (:position/average-entry-price pos)))
    (is (= 80.0 (:position/realized-pl pos)))))

(deftest short-close-realized-pl
  (let [fills [(fill 1 "X" :sell 100.0 11.0)
               (fill 1 "X" :buy 100.0 10.0)]
        closed (last-emission fills {:method :average})]
    (is (= :closed (:position/side closed)))
    (is (= 100.0 (:position/realized-pl closed)))))

(deftest closed-emitted-once
  (let [fills [(fill 1 "X" :buy 10.0 1.0)
               (fill 1 "X" :sell 10.0 2.0)]
        ems (emissions fills)]
    (is (= 2 (count ems)))
    (is (= :closed (:position/side (last ems))))))

(deftest ignores-non-fill-messages
  (let [flow (m/seed [{:type :trader/new-order :account/id 1 :asset "X" :side :buy :qty 1.0}
                      (fill 1 "X" :buy 1.0 5.0)])
        ems (emissions flow)]
    (is (= 1 (count ems)))
    (is (= :long (:position/side (first ems))))))

(deftest channel-paper-fills
  (let [flow (m/seed
              [{:type :trader/new-order :account/id 2 :order-id 4 :asset "ETHUSDT" :side :sell :qty 0.001}
               {:type :broker/order-filled :account/id 2 :order-id 4 :asset "ETHUSDT"
                :qty 0.001 :side :sell :price 100.0}
               {:type :broker/order-filled :account/id 2 :order-id 3 :asset "ETHUSDT"
                :qty 0.001 :side :sell :price 101.0}])
        ems (emissions flow)
        last-pos (last ems)]
    (is (= 2 (count ems)))
    (is (= :short (:position/side (first ems))))
    (is (= 0.001 (:position/qty (first ems))))
    (is (= :short (:position/side last-pos)))
    (is (= 0.002 (:position/qty last-pos)))
    (is (= 100.5 (:position/average-entry-price last-pos)))))
