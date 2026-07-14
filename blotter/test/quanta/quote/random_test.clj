(ns quanta.quote.random-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [quanta.quote.random :as random]))

(defn- two-decimal-places? [n]
  (= n (#'random/round-2 n)))

(deftest round-2-produces-two-decimal-places
  (testing "known values"
    (is (= 100.5 (#'random/round-2 100.499)))
    (is (= 106.03 (#'random/round-2 106.02942059303217))))
  (testing "idempotent"
    (doseq [n [0.0 1.23 99.99 100.0 1234.56]]
      (is (two-decimal-places? (#'random/round-2 n))))))

(deftest clamp-bounds-value
  (is (= -0.004 (random/clamp -1.0 -0.004 0.004)))
  (is (= 0.004 (random/clamp 1.0 -0.004 0.004)))
  (is (= 0.001 (random/clamp 0.001 -0.004 0.004))))

(deftest next-state-produces-two-decimal-places-and-clamped-trend
  (testing "iterated prices stay at 2dp and trend stays within clamp"
    (let [settings random/default-settings
          clamp (/ (:trend-clamp-prct settings) 100.0)
          states (take 50 (random/state-seq settings {:price 100.0 :trend 0.0}))]
      (is (every? #(two-decimal-places? (:price %)) states))
      (is (every? #(<= (- clamp) (:trend %) clamp) states)))))

(deftest initial-state-starts-with-zero-trend
  (is (= {:price 100.0 :trend 0.0} (random/initial-state 100.0))))
