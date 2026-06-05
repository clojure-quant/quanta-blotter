(ns quanta.blotter.oms.db-disk-test
  "Exercises the on-disk (file backend) datahike db. The db is created under
   blotter/test/test-db (git-ignored) and removed again after each test."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.java.io :as io]
   [tick.core :as t]
   [quanta.blotter.oms.db :as db]))

(def db-path "test/test-db")

(defn- delete-recursively [f]
  (let [f (io/file f)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-recursively child)))
      (.delete f))))

(defn- with-clean-db [t]
  (delete-recursively db-path) ; make sure we start clean
  (try
    (t)
    (finally
      (delete-recursively db-path))))

(use-fixtures :each with-clean-db)

(def demo-msg
  {:type :broker/order-filled :account/id 2 :order-id 4 :asset "ETHUSDT"
   :fill-id "m-9By0" :qty 0.001 :side :sell :price 100.0})

(def demo-order
  {:order/id 4 :order/account-id 2 :order/asset "ETHUSDT" :order/side :sell
   :order/type :limit :order/status :working :order/qty 0.001
   :order/qty-filled 0.0 :order/qty-working 0.001 :order/avg-price nil
   :order/date (t/instant) :order/history []})

(def demo-fill
  {:fill/id "m-9By0" :fill/order-id 4 :fill/account-id 2 :fill/asset "ETHUSDT"
   :fill/side :sell :fill/qty 0.001 :fill/price 100.0 :fill/date (t/instant)})

(def demo-position
  {:position/account 2 :position/asset "ETHUSDT" :position/side :short
   :position/open true :position/qty-open 0.001 :position/qty 0.001
   :position/average-entry-price 100.0 :position/realized-pl 0.0})

(deftest creates-db-on-disk-and-persists
  (let [conn (db/trade-db-start db-path)
        state (db/new-state)]
    (testing "the db directory is created on disk"
      (is (.exists (io/file db-path))))
    (db/process conn state [:msg demo-msg
                            :order demo-order
                            :fill demo-fill
                            :position demo-position])
    (testing "entities are queryable"
      (is (= 1 (count (db/query-messages conn))))
      (is (= 1 (count (db/query-orders conn))))
      (is (= 1 (count (db/query-fills conn))))
      (is (= 1 (count (db/query-positions conn)))))
    (db/trade-db-stop conn)))

(deftest data-survives-reconnect
  (let [conn (db/trade-db-start db-path)
        state (db/new-state)]
    (db/process conn state [:order demo-order])
    (db/trade-db-stop conn)
    (testing "reconnecting to the existing on-disk db sees the data"
      (let [conn2 (db/trade-db-start db-path)]
        (is (= 1 (count (db/query-orders conn2))))
        (is (= "4" (:order/id (first (db/query-orders conn2)))))
        (db/trade-db-stop conn2)))))
