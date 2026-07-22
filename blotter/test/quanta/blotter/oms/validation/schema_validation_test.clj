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

(defn- schema-error-logs [logs]
  (filter #(and (map? %) (contains? % :schema/error)) @logs))

(defn- with-validation-channel [f]
  (let [logs (atom [])
        log-fn #(swap! logs conj %)
        order-in (create-rdv "test/order-in")
        orderupdate-in (create-rdv "test/orderupdate-in")
        validator (vc/create-validation-channel {:order order-in
                                                 :orderupdate orderupdate-in
                                                 :log log-fn})
        {:keys [order orderupdate]} (:channel validator)
        _ (vc/start-validation-channel! validator)]
    (try
      (f {:output-order order
          :output-orderupdate orderupdate
          :inner-order order-in
          :inner-orderupdate orderupdate-in
          :logs logs})
      (finally
        (vc/stop-validation-channel! validator)))))

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

  (testing "invalid orderupdates are logged and not forwarded"
    (with-validation-channel
      (fn [{:keys [output-orderupdate inner-orderupdate logs]}]
        (doseq [msg original-orderupdates]
          (m/? (inner-orderupdate msg)))
        (let [errors (schema-error-logs logs)]
          (is (= (count original-orderupdates) (count errors)))
          (doseq [entry errors]
            (is (string? (:schema/error entry)))
            (is (map? (:original-msg entry)))
            (is (not (s/validate-message (:original-msg entry))))))
        (m/? (inner-orderupdate valid-orderupdate))
        (is (= :broker/order-confirmed (:type (m/? output-orderupdate)))
            "valid orderupdates still pass through"))))

  (testing "paper broker with bad-orderupdate-probability logs failed orderupdates"
    (with-redefs [qc/asset-quote-flow
                  (fn [_ _]
                    (m/seed (repeatedly 20 (fn []
                                             {:asset "BTCUSDT"
                                              :bid 100.0M
                                              :ask 100.01M
                                              :ts (t/instant)}))))]
      (let [logs (atom [])
            log-fn #(swap! logs conj %)
            order-in (create-rdv "test/order-in")
            orderupdate-in (create-rdv "test/orderupdate-in")
            validator (vc/create-validation-channel {:order order-in
                                                     :orderupdate orderupdate-in
                                                     :log log-fn})
            {:keys [order orderupdate]} (:channel validator)
            account {:account/id account-id
                     :account/api :paper
                     :account/settings {:reject-probability 0
                                        :fill-probability 100
                                        :fill-qty-prct [100]
                                        :ms-between-fills 0
                                        :bad-orderupdate-probability 100}}
            broker-task (p/create-trade-account {:quote-manager ::test-quote-manager}
                                                account order-in orderupdate-in log-fn)
            broker-dispose (broker-task (fn [_]) (fn [_]))
            _ (vc/start-validation-channel! validator)]
        (try
          (m/? (m/sp
                (m/? (order valid-new-order))
                (m/? (m/sleep 100))))
          (let [errors (schema-error-logs logs)]
            (is (pos? (count errors))
                "corrupted broker orderupdates produce schema/error log entries")
            (is (every? #(and (string? (:schema/error %))
                              (contains? % :original-msg))
                        errors)))
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
