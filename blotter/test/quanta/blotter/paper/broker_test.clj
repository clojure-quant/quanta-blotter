(ns quanta.blotter.paper.broker-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [quanta.blotter.protocol :as p]
   [quanta.blotter.paper.broker :as broker]
   [quanta.blotter.oms.validation.schema :as s]))

(def new-order
  {:type :trader/new-order
   :account/id 3
   :order-id 1
   :asset "BTCUSDT"
   :side :buy
   :limit 100.0M
   :qty 0.001M})

(deftest reject-reason-accepts-when-zero
  (is (every? nil? (repeatedly 200 #(broker/reject-reason 0))))
  (is (every? nil? (repeatedly 200 #(broker/reject-reason nil)))
      "missing reject-probability is treated as accept-all"))

(deftest reject-reason-rejects-when-hundred
  (let [reasons (repeatedly 200 #(broker/reject-reason 100))]
    (is (every? some? reasons))
    (is (every? (set broker/reject-reasons) reasons)
        "reason is always one of the documented reasons")))

(deftest reject-message-shape
  (let [msg (broker/reject-message new-order "market-closed")]
    (is (= :broker/order-rejected (:type msg)))
    (is (= "market-closed" (:reason msg)))
    (is (= 1 (:order-id msg)))
    (is (= 3 (:account/id msg)))
    (is (s/validate-message msg)
        (pr-str (s/human-error-message msg)))))

(defn- run-broker
  "Drives the paper broker with one new-order and collects the broker's
   updates until `n` messages have been pushed back. Returns the updates."
  [settings n]
  (let [to-broker (m/rdv)
        from-broker (m/rdv)
        account {:account/id 3 :account/api :paper :account/settings settings}
        task (p/create-trade-account account to-broker from-broker (fn [_]))
        dispose (task (fn [_]) (fn [_]))
        program (m/sp
                 (m/? (to-broker new-order))
                 (loop [acc []]
                   (if (= n (count acc))
                     acc
                     (recur (conj acc (m/? from-broker))))))
        updates (m/? program)]
    (dispose)
    updates))

(deftest rejects-without-confirm-or-fill
  (testing "reject-probability 100 emits only :broker/order-rejected"
    (let [[update] (run-broker {:reject-probability 100
                                :fill-probability 100
                                :fill-qty-prct [100]
                                :wait-seconds 0}
                               1)]
      (is (= :broker/order-rejected (:type update)))
      (is (= 1 (:order-id update)))
      (is ((set broker/reject-reasons) (:reason update))))))

(deftest accepts-and-fills-when-not-rejected
  (testing "reject-probability 0 confirms then fills"
    (let [updates (run-broker {:reject-probability 0
                               :fill-probability 100
                               :fill-qty-prct [100]
                               :wait-seconds 0}
                              2)
          types (map :type updates)]
      (is (= :broker/order-confirmed (first types)))
      (is (some #(= :broker/order-filled %) types))
      (is (not-any? #(= :broker/order-rejected %) types)))))
