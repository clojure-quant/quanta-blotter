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

(deftest next-price-produces-two-decimal-places
  (testing "iterated prices stay at 2dp"
    (let [prices (take 50 (iterate random/next-price 100.0))]
      (is (every? two-decimal-places? prices)))))
