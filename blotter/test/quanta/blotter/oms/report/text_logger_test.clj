(ns quanta.blotter.oms.report.text-logger-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [quanta.blotter.oms.report.text-logger :as text-logger]))

(deftest orderupdate-schema-errors-are-printed
  (let [schema-error {:type :broker/orderupdate-schema-error
                      :date #inst "2026-07-30T01:26:17.870Z"
                      :account/id 1000
                      :order-id "ysIJJF"
                      :campaign "limit-buy-sell-D1bwXpNU"
                      :message "spec-error {:date [\"must be a java.util.Date\"]}"}
        state (#'text-logger/acc-out-msg
               {:schema-error []}
               {:msg schema-error})
        output (#'text-logger/print-state state)]
    (is (= [schema-error] (:schema-error state)))
    (is (str/includes? output "schema errors:"))
    (is (str/includes? output ":broker/orderupdate-schema-error"))
    (is (str/includes? output (:message schema-error)))))

(deftest broker-snapshots-are-printed-as-account-tables
  (let [order-1 {:order/id "order-1"
                 :order/account-id 1000
                 :order/asset "EURUSD"
                 :order/side :buy
                 :order/qty 1000M
                 :order/type :limit
                 :order/status :working}
        order-2 (assoc order-1
                       :order/id "order-2"
                       :order/account-id 2000
                       :order/asset "GBPUSD")
        position-1 {:position/account 1000
                    :position/asset "EURUSD"
                    :position/side :long
                    :position/qty-entry 1000M
                    :position/qty-exit 0M
                    :position/qty-open 1000M}
        out-msgs [{:broker/working-orders
                   {:type :broker/working-orders
                    :account/id 1000
                    :orders [order-1]}}
                  {:broker/working-orders
                   {:type :broker/working-orders
                    :account/id 2000
                    :orders [order-2]}}
                  {:broker/working-orders
                   {:type :broker/working-orders
                    :account/id 3000
                    :orders []}}
                  {:broker/open-positions
                   {:type :broker/open-positions
                    :account/id 1000
                    :positions [position-1]}}
                  {:broker/open-positions
                   {:type :broker/open-positions
                    :account/id 3000
                    :positions []}}]
        state (reduce
               #'text-logger/acc-out-msg
               {:broker/working-orders []
                :broker/open-positions []}
               out-msgs)
        output (#'text-logger/print-state state)]
    (is (= 3 (count (:broker/working-orders state))))
    (is (= 2 (count (:broker/open-positions state))))
    (is (= 1 (count (re-seq #"working orders account id 1000:" output))))
    (is (= 1 (count (re-seq #"working orders account id 2000:" output))))
    (is (= 1 (count (re-seq #"open positions account id 1000:" output))))
    (is (not (str/includes? output "account id 3000:")))
    (is (str/includes? output "order-1"))
    (is (str/includes? output "order-2"))
    (is (str/includes? output "EURUSD"))
    (is (str/includes? output "GBPUSD"))))
