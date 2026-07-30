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
