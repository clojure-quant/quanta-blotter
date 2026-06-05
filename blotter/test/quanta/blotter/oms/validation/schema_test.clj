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
        :qty 0.001M
        :limit 100.0M}))
  (is (s/validate-message
       {:type :trader/new-order
        :account/id 1
        :order-id "abc"
        :asset "BTCUSDT"
        :side :sell
        :qty 0.001M}))
  (is (not (s/validate-message
            {:type :trader/new-order
             :account/id 1
             :order-id 1
             :asset "BTCUSDT"
             :side :long
             :qty 0.001}))))

(deftest trader-cancel-order-test
  (is (s/validate-message
       {:type :trader/cancel-order
        :account/id 2
        :order-id 2})))

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
