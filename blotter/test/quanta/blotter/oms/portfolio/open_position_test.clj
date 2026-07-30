(ns quanta.blotter.oms.portfolio.open-position-test
  (:require
   [clojure.test :refer [deftest is]]
   [quanta.blotter.oms.portfolio.open-position :as op]))

(defn- trade
  ([account asset side qty price]
   (trade account asset side qty price nil))
  ([account asset side qty price position-id]
   (cond-> {:fill/account-id account
            :fill/asset asset
            :fill/side side
            :fill/qty qty
            :fill/price price}
     position-id (assoc :fill/position-id position-id))))

(defn- apply-trades [trades]
  (reduce
   (fn [{:keys [open-position]} trade]
     (op/process-trade open-position trade))
   {:open-position {}}
   trades))

(deftest entries-use-average-cost-without-private-accumulators
  (let [{:keys [open-position positions-change]}
        (apply-trades [(trade 1 "X" :buy 50M 10.00M)
                       (trade 1 "X" :buy 50M 12.0000M)])
        position (get open-position [1 "X"])]
    (is (= [position] positions-change))
    (is (= 100M (:position/qty-entry position)))
    (is (= 0M (:position/qty-exit position)))
    (is (= 100M (:position/qty-open position)))
    (is (.equals 11.0000M (:position/average-entry-price position)))
    (is (= 4 (.scale ^BigDecimal (:position/average-entry-price position))))
    (is (false? (:position/hedge position)))
    (is (= 12 (count (:position/position-id position))))
    (is (not (contains? position :lots)))
    (is (not (contains? position :entry-notional)))
    (is (not (contains? position :exit-notional)))))

(deftest partial-exit-updates-exit-average-and-quantities
  (let [{:keys [open-position]}
        (apply-trades [(trade 1 "X" :buy 100M 10.00M)
                       (trade 1 "X" :sell 25M 12.0000M)
                       (trade 1 "X" :sell 15M 14.0M)])
        position (get open-position [1 "X"])]
    (is (= 100M (:position/qty-entry position)))
    (is (= 40M (:position/qty-exit position)))
    (is (= 60M (:position/qty-open position)))
    (is (.equals 10.00M (:position/average-entry-price position)))
    (is (.equals 12.7500M (:position/avg-exit-price position)))
    (is (= 4 (.scale ^BigDecimal (:position/avg-exit-price position))))
    (is (= 110.0000M (:position/realized-pl position)))))

(deftest short-close-realized-pl
  (let [{:keys [open-position positions-change position-closed]}
        (apply-trades [(trade 1 "X" :sell 100M 11.0M)
                       (trade 1 "X" :buy 100M 10.00M)])
        closed (first positions-change)]
    (is (empty? open-position))
    (is (= closed position-closed))
    (is (not (op/position-open? closed)))
    (is (= :short (:position/side closed)))
    (is (= 100M (:position/qty-entry closed)))
    (is (= 100M (:position/qty-exit closed)))
    (is (= 0M (:position/qty-open closed)))
    (is (.equals 10.00M (:position/avg-exit-price closed)))
    (is (= 100.00M (:position/realized-pl closed)))
    (is (instance? java.util.Date (:position/date-close closed)))))

(deftest normal-account-flip-closes-and-opens-separate-positions
  (let [{:keys [open-position]} (op/process-trade {}
                                                  (trade 1 "X" :buy 100M 10.0M))
        old-position (get open-position [1 "X"])
        {:keys [open-position positions-change position-closed] :as result}
        (op/process-trade open-position (trade 1 "X" :sell 110M 11.00M))
        [closed opened] positions-change]
    (is (not (contains? result :position)))
    (is (= 2 (count positions-change)))
    (is (= closed position-closed))
    (is (not (op/position-open? closed)))
    (is (= 100M (:position/qty-entry closed)))
    (is (= 100M (:position/qty-exit closed)))
    (is (= 100M (:position/realized-pl closed)))
    (is (= (:position/position-id old-position)
           (:position/position-id closed)))
    (is (op/position-open? opened))
    (is (= :short (:position/side opened)))
    (is (= 10M (:position/qty-entry opened)))
    (is (= 0M (:position/qty-exit opened)))
    (is (= 10M (:position/qty-open opened)))
    (is (= 0M (:position/realized-pl opened)))
    (is (not= (:position/position-id closed)
              (:position/position-id opened)))
    (is (= {[1 "X"] opened} open-position))))

(deftest hedge-positions-use-position-id-dictionary-keys
  (let [{:keys [open-position]}
        (apply-trades [(trade 1 "X" :buy 10M 10M "long-1")
                       (trade 1 "X" :sell 20M 11M "short-1")])]
    (is (= #{"long-1" "short-1"} (set (keys open-position))))
    (is (= "long-1"
           (get-in open-position ["long-1" :position/position-id])))
    (is (true? (get-in open-position ["long-1" :position/hedge])))
    (is (= :short (get-in open-position ["short-1" :position/side])))))

(deftest hedge-close-overshoot-does-not-flip
  (let [{:keys [open-position]}
        (op/process-trade {} (trade 1 "X" :buy 10M 10M "hedge-1"))
        {:keys [open-position positions-change position-closed]}
        (op/process-trade open-position
                          (trade 1 "X" :sell 15M 12M "hedge-1"))
        closed (first positions-change)]
    (is (= 1 (count positions-change)))
    (is (= closed position-closed))
    (is (not (op/position-open? closed)))
    (is (= 10M (:position/qty-exit closed)))
    (is (= 20M (:position/realized-pl closed)))
    (is (empty? open-position))))

(deftest hydrate-position-preserves-public-state
  (let [persisted {:position/account 1
                   :position/asset "X"
                   :position/side :long
                   :position/qty-entry 10M
                   :position/qty-exit 4M
                   :position/qty-open 6M
                   :position/average-entry-price 1.0900M
                   :position/avg-exit-price 2.345678M
                   :position/realized-pl 5M
                   :position/date-open nil
                   :position/date-close nil
                   :position/position-id "hedge-1"
                   :position/hedge true}
        position (op/hydrate-position persisted)]
    (is (= persisted position))
    (is (= "hedge-1" (op/position-key position)))
    (is (.equals 1.0900M (:position/average-entry-price position)))
    (is (.equals 2.345678M (:position/avg-exit-price position)))))

(deftest process-trade-without-trade-is-sparse
  (is (= {} (op/process-trade {} nil))))

(deftest position-open-is-derived-from-open-quantity
  (is (op/position-open? {:position/qty-open 1M}))
  (is (not (op/position-open? {:position/qty-open 0M})))
  (is (not (op/position-open? {:position/qty-open nil})))
  (is (not (op/position-open? {}))))
