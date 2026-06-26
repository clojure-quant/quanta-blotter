(ns quanta.blotter.oms.validation.schema-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ednx.edn :refer [read-edn]]
   [ednx.tick.edn :refer [add-tick-edn-handlers!]]
   [malli.core :as m]
   [tick.core :as t]
   [quanta.blotter.oms.validation.schema :as s]))

(add-tick-edn-handlers!)

(deftest decimal-test
  (is (not (m/validate s/Decimal 100.3 {:registry s/r})))
  (is (m/validate s/Decimal 100.3M {:registry s/r})))

(def ^:private channel-paper-edn
  (io/file ".." "demo" "data" "channel-paper.edn"))

(defn- read-edn-file [path]
  (-> path slurp read-edn))

(deftest trader-new-order-test
  (is (s/validate-message
       {:type :trader/new-order
        :account/id 1
        :order-id 1
        :asset "BTCUSDT"
        :side :buy
        :order-type :limit
        :qty 0.001M
        :limit 100.0M}))
  (is (s/validate-message
       {:type :trader/new-order
        :account/id 1
        :order-id "abc"
        :asset "BTCUSDT"
        :side :sell
        :order-type :market
        :qty 0.001M}))
  (is (not (s/validate-message
            {:type :trader/new-order
             :account/id 1
             :order-id 1
             :asset "BTCUSDT"
             :side :long
             :order-type :limit
             :qty 0.001})))
  (is (not (s/validate-message
            {:type :trader/new-order
             :account/id 1
             :order-id 1
             :asset "BTCUSDT"
             :side :buy
             :order-type :limit
             :qty 0.001M}))
      "limit order without :limit is invalid")
  (is (not (s/validate-message
            {:type :trader/new-order
             :account/id 1
             :order-id 1
             :asset "BTCUSDT"
             :side :buy
             :order-type :market
             :qty 0.001M
             :limit 100.0M}))
      "market order with :limit is invalid")
  (is (not (s/validate-message
            {:type :trader/new-order
             :account/id 1
             :order-id 1
             :asset "BTCUSDT"
             :side :buy
             :qty 0.001M
             :limit 100.0M}))
      "missing :order-type is invalid"))

(deftest trader-new-order-campaign-and-label-test
  (is (s/validate-message
       {:type :trader/new-order
        :account/id 1
        :order-id 1
        :asset "BTCUSDT"
        :side :buy
        :order-type :limit
        :qty 0.001M
        :limit 100.0M
        :campaign "fx-q2"
        :label :hedge}))
  (is (not (s/validate-message
            {:type :trader/new-order
             :account/id 1
             :order-id 1
             :asset "BTCUSDT"
             :side :buy
             :order-type :limit
             :qty 0.001M
             :limit 100.0M
             :campaign :not-a-string}))
      "campaign must be a string")
  (is (not (s/validate-message
            {:type :trader/new-order
             :account/id 1
             :order-id 1
             :asset "BTCUSDT"
             :side :buy
             :order-type :limit
             :qty 0.001M
             :limit 100.0M
             :label "not-a-keyword"}))
      "label must be a keyword"))

(deftest broker-order-confirmed-campaign-and-label-test
  (is (s/validate-message
       {:type :broker/order-confirmed
        :account/id 1
        :order-id 1
        :asset "BTCUSDT"
        :side :buy
        :order-type :limit
        :qty 0.001M
        :limit 100.0M
        :date (t/instant)
        :campaign "fx-q2"
        :label :hedge})))

(deftest trader-cancel-order-test
  (is (s/validate-message
       {:type :trader/cancel-order
        :account/id 2
        :order-id 2
        :asset "ETHUSDT"}))
  (is (not (s/validate-message
            {:type :trader/cancel-order
             :account/id 2
             :order-id 2}))
      "cancel-order without :asset is invalid"))

(deftest broker-order-filled-test
  (is (s/validate-message
       {:type :broker/order-filled
        :account/id 2
        :order-id 4
        :fill-id "m-9By0"
        :date (t/instant)
        :asset "ETHUSDT"
        :qty 0.001M
        :side :sell
        :price 100.0M})))

(deftest broker-order-rejected-test
  (is (s/validate-message
       {:type :broker/order-rejected
        :account/id 3
        :order-id 7
        :date (t/instant)
        :message "market-closed"}))
  (is (s/validate-message
       {:type :broker/order-rejected
        :account/id 3
        :order-id 7})
      "date/message are optional"))

(deftest broker-cancel-rejected-test
  (is (s/validate-message
       {:type :broker/cancel-rejected
        :account/id 2
        :order-id 2
        :message "unknown order"}))
  (is (s/validate-message
       {:type :broker/cancel-rejected
        :account/id 2
        :order-id 2})
      "message is optional")
  (is (not (s/validate-message
            {:type :broker/cancel-rejected
             :account/id 2
             :message "missing order-id"}))))

(deftest broker-modify-rejected-test
  (is (s/validate-message
       {:type :broker/modify-rejected
        :account/id 2
        :order-id 2
        :message "order not modifiable"}))
  (is (s/validate-message
       {:type :broker/modify-rejected
        :account/id 2
        :order-id 2})
      "message is optional")
  (is (not (s/validate-message
            {:type :broker/modify-rejected
             :account/id 2
             :message "missing order-id"}))))

(deftest validate-trader-message-test
  (testing "valid new-orders"
    (doseq [msg [{:type :trader/new-order
                  :account/id 1
                  :order-id 1
                  :asset "BTCUSDT"
                  :side :buy
                  :order-type :limit
                  :qty 0.001M
                  :limit 100.0M}
                 {:type :trader/new-order
                  :account/id 2
                  :order-id "ord-42"
                  :asset "ETHUSDT"
                  :side :sell
                  :order-type :market
                  :qty 1.5M}
                 {:type :trader/new-order
                  :account/id 3
                  :order-id 7
                  :asset "BTCUSDT"
                  :side :buy
                  :order-type :stop
                  :qty 0.01M
                  :limit 95000.0M
                  :campaign "fx-q2"
                  :label :hedge}]]
      (is (s/validate-trader-message msg) (str "expected valid: " (pr-str msg)))))

  (testing "invalid new-orders"
    (doseq [msg [{:type :trader/new-order
                  :account/id 1
                  :order-id 1
                  :asset "BTCUSDT"
                  :side :buy
                  :order-type :limit
                  :qty 0.001M}
                 {:type :trader/new-order
                  :account/id 1
                  :order-id 1
                  :asset "BTCUSDT"
                  :side :buy
                  :order-type :market
                  :qty 0.001M
                  :limit 100.0M}
                 {:type :trader/new-order
                  :account/id 1
                  :order-id 1
                  :asset "BTCUSDT"
                  :side :long
                  :order-type :limit
                  :qty 0.001M
                  :limit 100.0M}
                 {:type :trader/new-order
                  :account/id 1
                  :order-id 1
                  :asset "BTCUSDT"
                  :side :buy
                  :order-type :limit
                  :qty 0.001
                  :limit 100.0M}
                 {:type :trader/new-order
                  :account/id 1
                  :order-id 1
                  :asset "BTCUSDT"
                  :side :buy
                  :order-type :limit
                  :qty -0.001M
                  :limit 100.0M}]]
      (is (not (s/validate-trader-message msg)) (str "expected invalid: " (pr-str msg)))))

  (testing "valid cancel-orders"
    (doseq [msg [{:type :trader/cancel-order
                  :account/id 1
                  :order-id 1
                  :asset "BTCUSDT"}
                 {:type :trader/cancel-order
                  :account/id 2
                  :order-id "ord-42"
                  :asset "ETHUSDT"}]]
      (is (s/validate-trader-message msg) (str "expected valid: " (pr-str msg)))))

  (testing "invalid cancel-orders"
    (doseq [msg [{:type :trader/cancel-order
                  :order-id 1
                  :asset "BTCUSDT"}
                 {:type :trader/cancel-order
                  :account/id 1
                  :asset "BTCUSDT"}
                 {:type :trader/cancel-order
                  :account/id 1
                  :order-id 1}
                 {:type :trader/cancel-order
                  :account/id "not-an-int"
                  :order-id 1
                  :asset "BTCUSDT"}]]
      (is (not (s/validate-trader-message msg)) (str "expected invalid: " (pr-str msg)))))

  (testing "valid modify-orders"
    (doseq [msg [{:type :trader/modify-order
                  :account/id 1
                  :order-id 1
                  :asset "BTCUSDT"
                  :qty 0.002M}
                 {:type :trader/modify-order
                  :account/id 2
                  :order-id "ord-42"
                  :asset "ETHUSDT"
                  :limit 99.5M}
                 {:type :trader/modify-order
                  :account/id 3
                  :order-id 7
                  :asset "BTCUSDT"
                  :qty 0.5M
                  :limit 101.0M}]]
      (is (s/validate-trader-message msg) (str "expected valid: " (pr-str msg)))))

  (testing "invalid modify-orders"
    (doseq [msg [{:type :trader/modify-order
                  :order-id 1
                  :asset "BTCUSDT"
                  :qty 0.002M}
                 {:type :trader/modify-order
                  :account/id 1
                  :order-id 1
                  :qty 0.002M}
                 {:type :trader/modify-order
                  :account/id 1
                  :order-id 1
                  :asset "BTCUSDT"
                  :qty 0.0M}
                 {:type :trader/modify-order
                  :account/id 1
                  :order-id 1
                  :asset "BTCUSDT"
                  :qty "not-a-decimal"}]]
      (is (not (s/validate-trader-message msg)) (str "expected invalid: " (pr-str msg)))))

  (testing "broker messages are not trader messages"
    (doseq [msg [{:type :broker/order-filled
                  :account/id 1
                  :order-id 1
                  :fill-id "f-1"
                  :date (t/instant)
                  :asset "BTCUSDT"
                  :qty 0.001M
                  :side :buy
                  :price 100.0M}
                 {:type :broker/order-rejected
                  :account/id 1
                  :order-id 1}]]
      (is (not (s/validate-trader-message msg))
          "validate-trader-message rejects broker messages"))))

(deftest channel-paper-edn-schema-test
  (is (.exists channel-paper-edn)
      (str "demo fixture missing: " (.getAbsolutePath channel-paper-edn)))
  (let [messages (read-edn-file channel-paper-edn)]
    (is (sequential? messages))
    (is (= 20 (count messages)) "every message in channel-paper.edn is validated")
    (doseq [msg messages]
      (testing (str (:type msg)
                    (when-let [id (:order-id msg)] (str " order-id=" id))
                    (when-let [aid (:account/id msg)] (str " account/id=" aid)))
        (is (s/validate-message msg)
            (pr-str (s/human-error-message msg)))))))
