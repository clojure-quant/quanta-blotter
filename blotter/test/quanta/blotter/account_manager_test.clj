(ns quanta.blotter.account-manager-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [quanta.blotter.account-manager :refer [read-account-orderupdate]]))

(defn- read-update [account-id update]
  (m/?
   (read-account-orderupdate
    {:account/id account-id
     :account-orderupdate-rdf (m/sp update)})))

(deftest incoming-orderupdate-date-normalization
  (testing "java.time.Instant is normalized to java.util.Date"
    (let [instant (java.time.Instant/parse "2026-07-30T01:36:54.365Z")
          update (read-update 1000 {:type :broker/order-confirmed
                                    :date instant})]
      (is (instance? java.util.Date (:date update)))
      (is (= instant (.toInstant ^java.util.Date (:date update))))
      (is (= 1000 (:account/id update)))))

  (testing "java.util.Date keeps its timestamp"
    (let [date #inst "2026-07-30T01:36:54.365Z"
          update (read-update 2 {:type :broker/order-filled
                                 :date date})]
      (is (instance? java.util.Date (:date update)))
      (is (= date (:date update)))))

  (testing "missing date is supplied"
    (let [before (java.time.Instant/now)
          update (read-update 1 {:type :broker/order-modified})
          after (java.time.Instant/now)
          update-instant (.toInstant ^java.util.Date (:date update))]
      (is (instance? java.util.Date (:date update)))
      (is (not (.isBefore update-instant (.minusSeconds before 1))))
      (is (not (.isAfter update-instant after))))))
