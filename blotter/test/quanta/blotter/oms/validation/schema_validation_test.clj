(ns quanta.blotter.oms.validation.schema-validation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [quanta.blotter.protocol :as p]
   [quanta.blotter.util-rdv :refer [create-rdv]]
   [quanta.blotter.oms.validation.channel :as vc]
   [quanta.blotter.oms.validation.schema :as s]
   [quanta.market-sim.broker-paper :as broker]
   [quanta.quote.core :as qc]
   [tick.core :as t]))

(def account-id 3)

(def valid-new-order
  {:type :trader/new-order
   :account/id account-id
   :order-id 1
   :asset "BTCUSDT"
   :side :buy
   :order-type :limit
   :limit 100.0M
   :qty 0.001M})

(def valid-orderupdate
  {:type :broker/order-confirmed
   :account/id account-id
   :order-id 99
   :asset "BTCUSDT"
   :side :buy
   :order-type :limit
   :qty 0.001M
   :limit 100.0M
   :date (t/instant)})

(defn- bad-order [base]
  (case (rand-int 3)
    0 (dissoc base :qty)
    1 (assoc base :side :long)
    2 (assoc base :qty -1M)))

(def original-orders
  (let [bad (bad-order (assoc valid-new-order :order-id 2))]
    [valid-new-order bad]))

(def original-orderupdates
  (let [confirmed {:type :broker/order-confirmed
                   :account/id account-id
                   :order-id 99
                   :asset "BTCUSDT"
                   :side :buy
                   :order-type :limit
                   :qty 0.001M
                   :limit 100.0M
                   :date (t/instant)}
        filled {:type :broker/order-filled
                :account/id account-id
                :order-id 99
                :fill-id "f-1"
                :date (t/instant)
                :asset "BTCUSDT"
                :qty 0.001M
                :side :buy
                :price 100.0M}]
    [(case (rand-int 3)
       0 (dissoc confirmed :limit)
       1 (assoc confirmed :qty "not-a-decimal")
       2 (assoc confirmed :side :long))
     (case (rand-int 3)
       0 (dissoc filled :qty)
       1 (assoc filled :side :long)
       2 (assoc filled :qty "not-a-decimal"))]))

(defn- with-validation-channel [f]
  (let [order-in (create-rdv "test/order-in")
        orderupdate-in (create-rdv "test/orderupdate-in")
        validator (vc/create-validation-channel {:order order-in
                                                 :orderupdate orderupdate-in})
        {:keys [order orderupdate]} (:channel validator)
        _ (vc/start-validation-channel! validator)]
    (try
      (f {:output-order order
          :output-orderupdate orderupdate
          :inner-order order-in
          :inner-orderupdate orderupdate-in})
      (finally
        (vc/stop-validation-channel! validator)))))

(defn- take-until-timeout [rdv timeout-ms]
  "Takes from `rdv` until timeout; returns collected values."
  (m/sp
   (loop [acc []]
     (let [v (m/? (m/race rdv (m/sleep timeout-ms ::timeout)))]
       (if (= v ::timeout)
         acc
         (recur (conj acc v)))))))

(deftest schema-validation-test
  (testing "invalid orders are rejected on the output orderupdate channel"
    (with-validation-channel
      (fn [{:keys [output-order output-orderupdate]}]
        (let [bad (second original-orders)]
          (m/? (output-order bad))
          (let [update (m/? output-orderupdate)]
            (is (= :broker/order-rejected (:type update)))
            (is (re-find #"^spec-error" (:message update)))
            (is (not (s/validate-message bad))))))))

  (testing "invalid orderupdates are rejected on the output orderupdate channel"
    (with-validation-channel
      (fn [{:keys [output-orderupdate inner-orderupdate]}]
        (doseq [msg original-orderupdates]
          (m/? (inner-orderupdate msg))
          (let [update (m/? output-orderupdate)]
            (is (= :broker/orderupdate-schema-error (:type update)))
            (is (re-find #"^spec-error" (:message update)))
            (is (= account-id (:account/id update)))
            (is (= 99 (:order-id update)))
            (is (not (s/validate-message msg)))))
        (m/? (inner-orderupdate valid-orderupdate))
        (is (= :broker/order-confirmed (:type (m/? output-orderupdate)))
            "valid orderupdates still pass through"))))

  (testing "paper broker with bad-orderupdate-probability emits orderupdate-schema-error"
    (with-redefs [qc/asset-quote-flow
                  (fn [_ _]
                    (m/seed (repeatedly 20 (fn []
                                             {:asset "BTCUSDT"
                                              :bid 100.0M
                                              :ask 100.01M
                                              :ts (t/instant)}))))]
      (let [order-in (create-rdv "test/order-in")
            orderupdate-in (create-rdv "test/orderupdate-in")
            validator (vc/create-validation-channel {:order order-in
                                                     :orderupdate orderupdate-in})
            {:keys [order orderupdate]} (:channel validator)
            account {:account/id account-id
                     :account/api :paper
                     :account/settings {:reject-probability 0
                                        :fill-probability 100
                                        :fill-qty-prct [100]
                                        :ms-between-fills 0
                                        :bad-orderupdate-probability 100}}
            broker-task (p/create-trade-account {:quote-manager ::test-quote-manager}
                                                account order-in orderupdate-in (fn [_]))
            broker-dispose (broker-task (fn [_]) (fn [_]))
            _ (vc/start-validation-channel! validator)]
        (try
          (m/? (order valid-new-order))
          (let [updates (m/? (take-until-timeout orderupdate 300))
                rejects (filter #(= :broker/orderupdate-schema-error (:type %)) updates)]
            (is (pos? (count rejects))
                "corrupted broker orderupdates produce :broker/orderupdate-schema-error")
            (is (every? #(re-find #"^spec-error" (:message %)) rejects)))
          (finally
            (vc/stop-validation-channel! validator)
            (broker-dispose)))))))

(deftest corrupt-message-fails-validation
  (let [confirmed {:type :broker/order-confirmed
                   :account/id 1
                   :order-id 1
                   :asset "BTCUSDT"
                   :side :buy
                   :order-type :limit
                   :qty 0.001M
                   :limit 100.0M
                   :date (t/instant)}]
    (is (s/validate-message confirmed))
    (dotimes [_ 30]
      (is (not (s/validate-message (broker/corrupt-message confirmed)))
          "corrupt-message always produces invalid broker messages"))))

(deftest bad-orderupdate-probability-always-corrupts
  (is (every? true? (repeatedly 50 #(broker/bad-orderupdate? 100))))
  (is (every? false? (repeatedly 50 #(broker/bad-orderupdate? 0)))))
