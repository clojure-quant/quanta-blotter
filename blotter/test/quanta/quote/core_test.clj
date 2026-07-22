(ns quanta.quote.core-test
  "Tests quanta.quote.core/calc-id against a disk-backed asset db seeded like
   demo.quote-asset-list-print."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [clojure.java.io :as io]
   [quanta.asset.schema]
   [quanta.asset.seed :as asset-seed]
   [quanta.blotter.oms.db :as oms-db]
   [quanta.quote.core :as quote-core]
   [quanta.util.datahike :as datahike]))

(def db-path "test/asset-db")
(def assets-file "../demo/demo-assets.edn")

(defn- delete-recursively [f]
  (let [f (io/file f)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-recursively child)))
      (.delete f))))

(defn- with-clean-db [t]
  (delete-recursively db-path)
  (try
    (t)
    (finally
      (delete-recursively db-path))))

(use-fixtures :each with-clean-db)

(defn- seeded-quote-db []
  (datahike/db-start
   {:schema (concat oms-db/schema quanta.asset.schema/asset)
    :db-path db-path
    :seed-fn [(asset-seed/seed-edn-assets-fn assets-file)]}))

(deftest calc-id-returns-default-quote-account
  (let [db (seeded-quote-db)
        qm {:db db}]
    (try
      (testing "fx asset uses ctrader quote account"
        (is (= 1 (quote-core/calc-id qm "EURUSD"))))
      (testing "crypto spot asset uses bybit spot quote account"
        (is (= 3 (quote-core/calc-id qm "BTCUSDT.S.BB"))))
      (testing "crypto linear future uses bybit linear quote account"
        (is (= 2 (quote-core/calc-id qm "BTCUSDT.LF.BB"))))
      (testing "paper/random asset uses random quote account"
        (is (= 4 (quote-core/calc-id qm "__TEST"))))
      (finally
        (datahike/db-stop db)))))
