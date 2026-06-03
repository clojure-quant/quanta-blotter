(ns quanta.blotter.oms.db-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [datahike.api :as d]
   [tick.core :as t]
   [quanta.blotter.oms.db :as db]))

(defn- fresh-db []
  (db/trade-db-start-mem))

(def demo-msg
  {:type :broker/order-filled :account/id 2 :order-id 4 :asset "ETHUSDT"
   :fill-id "m-9By0" :qty 0.001 :side :sell :price 100.0})

(def demo-order
  {:order/id 4 :order/account 2 :order/asset "ETHUSDT" :order/side :sell
   :order/status :working :order/qty 0.001 :order/qty-filled 0.0
   :order/qty-working 0.001 :order/avg-price nil})

(def demo-order-update
  {:order/id 4 :order/account 2 :order/asset "ETHUSDT" :order/side :sell
   :order/status :done :order/qty 0.001 :order/qty-filled 0.001
   :order/qty-working 0.0 :order/avg-price 100.0})

(def demo-fill
  {:type :broker/order-filled :account/id 2 :order-id 4 :asset "ETHUSDT"
   :fill-id "m-9By0" :qty 0.001 :side :sell :price 100.0})

(def demo-position
  {:position/account 2 :position/asset "ETHUSDT" :position/side :short
   :position/qty 0.001 :position/average-entry-price 100.0 :position/realized-pl 0.0})

(deftest process-stores-all-kinds
  (let [conn (fresh-db)
        state (db/new-state)]
    (db/process conn state [:msg demo-msg
                            :order demo-order
                            :fill demo-fill
                            :position demo-position])
    (testing "message stored"
      (let [msgs (db/query-messages conn)]
        (is (= 1 (count msgs)))
        (is (= :broker/order-filled (:message/type (first msgs))))
        (is (= 2 (:message/account-id (first msgs))))))
    (testing "order stored"
      (let [orders (db/query-orders conn)]
        (is (= 1 (count orders)))
        (is (= "4" (:order/id (first orders))))
        (is (= :working (:order/status (first orders))))))
    (testing "fill stored once with order ref"
      (let [fills (db/query-fills conn)]
        (is (= 1 (count fills)))
        (is (= "m-9By0" (:fill/id (first fills))))
        (is (some? (:fill/order (first fills))) "fill references its order")))
    (testing "position stored"
      (let [positions (db/query-positions conn)]
        (is (= 1 (count positions)))
        (is (= :short (:position/side (first positions))))))
    (db/trade-db-stop conn)))

(deftest order-update-reuses-db-id
  (let [conn (fresh-db)
        state (db/new-state)]
    (db/process conn state [:order demo-order])
    (let [eid-after-create (get-in @state [:order-id->eid "4"])]
      (db/process conn state [:order demo-order-update])
      (testing "same :db/id reused for the update"
        (is (= eid-after-create (get-in @state [:order-id->eid "4"])))
        (is (= 1 (count (db/query-orders conn))) "no duplicate order entity")
        (is (= :done (:order/status (first (db/query-orders conn)))))))
    (db/trade-db-stop conn)))

(deftest instant-date-coerced-to-date
  (testing "a java.time.Instant :date is coerced to java.util.Date and persisted"
    (let [conn (fresh-db)
          state (db/new-state)
          msg (assoc demo-msg :date (t/instant))]
      (db/process conn state [:msg msg])
      (let [stored (:message/date (first (db/query-messages conn)))]
        (is (instance? java.util.Date stored)))
      (db/trade-db-stop conn))))

(deftest fill-stored-only-once
  (let [conn (fresh-db)
        state (db/new-state)]
    (db/process conn state [:fill demo-fill])
    (db/process conn state [:fill demo-fill])
    (is (= 1 (count (db/query-fills conn))) "duplicate fill ignored")
    (db/trade-db-stop conn)))
