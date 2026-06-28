(ns quanta.blotter.oms.db-account-test
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [quanta.blotter.oms.db :as db]
   [quanta.util.datahike :as datahike]))

(def demo-accounts-path "../demo/demo-accounts.edn")

(defn- load-demo-accounts []
  (-> demo-accounts-path slurp edn/read-string))

(defn- account-fields [account]
  (select-keys account [:account/id :account/api :account/trader
                        :account/notes :account/settings]))

(defn- accounts-by-trader [accounts trader]
  (filter #(= trader (:account/trader %)) accounts))

(defn- assert-trader-accounts [conn edn-accounts trader]
  (let [expected (accounts-by-trader edn-accounts trader)
        actual (db/trader-account-list conn trader)]
    (is (= (count expected) (count actual))
        (str "account count for trader " trader))
    (doseq [expected-account expected]
      (let [actual-account (first (filter #(= (:account/id expected-account)
                                             (:account/id %))
                                          actual))]
        (is (some? actual-account)
            (str "missing account " (:account/id expected-account) " for trader " trader))
        (when actual-account
          (is (= (account-fields expected-account)
                 (account-fields actual-account))
              (str "account " (:account/id expected-account) " fields match")))))))

(deftest demo-accounts-roundtrip
  (let [edn-accounts (load-demo-accounts)
        conn (datahike/db-start-mem db/schema)]
    (try
      (doseq [account edn-accounts]
        (db/create-account conn (select-keys account [:account/id :account/trader :account/api]))
        (db/update-account conn (select-keys account [:account/id :account/notes
                                                      :account/settings :account/name])))
      (testing "trader account lists match demo-accounts.edn"
        (assert-trader-accounts conn edn-accounts "florian")
        (assert-trader-accounts conn edn-accounts "arne")
        (assert-trader-accounts conn edn-accounts "ant"))
      (testing "enable/disable filtering"
        (doseq [id [1000 1 2 3]]
          (db/enable-account conn id true))
        (db/enable-account conn 2 false)
        (let [enabled-ids (set (map :account/id (db/all-enabled-accounts conn)))]
          (is (= #{1000 1 3} enabled-ids))))
      (finally
        (datahike/db-stop conn)))))
