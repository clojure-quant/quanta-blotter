(ns quanta.blotter.oms.portfolio.hydrate-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tick.core :as t]
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.portfolio :as portfolio]
   [quanta.util.datahike :as datahike]))

(defn- fresh-db []
  (datahike/db-start-mem db/schema))

(deftest hydrate-from-db-loads-only-open-orders-and-positions
  (let [conn (fresh-db)
        state (db/new-state)
        open-order {:order/id "wo-1" :order/account-id 1 :order/asset "EURUSD"
                    :order/side :buy :order/type :limit :order/status :working
                    :order/qty 1000.0M :order/qty-filled 0.0M :order/qty-working 1000.0M
                    :order/avg-price nil :order/limit 1.10M :order/date (t/inst)
                    :order/history []}
        filled-order {:order/id "cl-1" :order/account-id 1 :order/asset "EURUSD"
                      :order/side :buy :order/type :market :order/status :filled
                      :order/qty 500.0M :order/qty-filled 500.0M :order/qty-working 0.0M
                      :order/avg-price 1.09M :order/date (t/inst)
                      :order/history []}
        open-pos {:position/account 1 :position/asset "EURUSD" :position/side :long
                  :position/qty-entry 500.0M :position/qty-exit 0M
                  :position/qty-open 500.0M :position/position-id "normal-1"
                  :position/hedge false :position/average-entry-price 1.09M
                  :position/realized-pl 0.0M}
        hedge-pos {:position/account 1 :position/asset "EURUSD" :position/side :short
                   :position/qty-entry 100.0M :position/qty-exit 0M
                   :position/qty-open 100.0M :position/position-id "hedge-1"
                   :position/hedge true :position/average-entry-price 1.11M
                   :position/realized-pl 0.0M}
        closed-pos {:position/account 2 :position/asset "GBPUSD" :position/side :short
                    :position/qty-entry 100.0M :position/qty-exit 100.0M
                    :position/qty-open 0.0M :position/position-id "normal-2"
                    :position/hedge false :position/average-entry-price 1.25M
                    :position/realized-pl 5.0M}]
    (db/persist-block conn state [:order open-order
                            :order filled-order
                            :position open-pos
                            :position hedge-pos
                            :position closed-pos])
    (let [hydrated (#'portfolio/hydrate-from-db conn)]
      (testing "working-order dict has only open orders"
        (is (= #{"wo-1"} (set (keys (:working-order hydrated)))))
        (is (= :working (get-in hydrated [:working-order "wo-1" :order/status]))))
      (testing "no parallel order-accs"
        (is (nil? (:order-accs hydrated))))
      (testing "open-position dict has only open positions"
        (is (= #{[1 "EURUSD"] "hedge-1"}
               (set (keys (:open-position hydrated)))))
        (is (== 500.0M (get-in hydrated [:open-position [1 "EURUSD"] :position/qty-open])))
        (is (true? (get-in hydrated [:open-position "hedge-1" :position/hedge])))
        (is (not (contains? (get-in hydrated [:open-position [1 "EURUSD"]])
                            :lots))))
      (testing "no parallel position-accs"
        (is (nil? (:position-accs hydrated)))))
    (datahike/db-stop conn)))
