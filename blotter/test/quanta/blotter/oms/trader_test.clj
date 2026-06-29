(ns quanta.blotter.oms.trader-test
  (:require
   [clojure.test :refer [deftest is]]
   [missionary.core :as m]
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.trader :as trader]
   [quanta.util.datahike :as datahike]))

(def accounts-by-id
  {1 {:account/trader "florian" :account/name "paper-1"}
   2 {:account/trader "arne" :account/name "paper-2"}
   3 {:account/trader "ant" :account/name "paper-3"}})

(defn- setup-test-db []
  (let [conn (datahike/db-start-mem db/schema)]
    (doseq [[id {:keys [:account/trader :account/name]}] accounts-by-id]
      (db/create-account conn {:account/id id :account/trader trader :account/api :paper})
      (db/update-account conn {:account/id id :account/name name}))
    conn))

(defn- assert-all-tagged [amended]
  (doseq [order (:working-orders amended)]
    (let [{:keys [:account/trader :account/name]} (get accounts-by-id (:order/account-id order))]
      (is (= trader (:order/trader order)))
      (is (= name (:order/account-name order)))))
  (doseq [pos (:open-positions amended)]
    (let [{:keys [:account/trader :account/name]} (get accounts-by-id (:position/account pos))]
      (is (= trader (:position/trader pos)))
      (is (= name (:position/account-name pos))))))

(def snapshots
  [{:working-orders [] :open-positions []}
   {:working-orders [{:order/id 1 :order/account-id 1 :order/asset "BTC"}]
    :open-positions []}
   {:working-orders [{:order/id 1 :order/account-id 1 :order/asset "BTC"}]
    :open-positions [{:position/account 2 :position/asset "ETH" :position/side :long}]}
   {:working-orders [{:order/id 1 :order/account-id 1 :order/asset "BTC"}
                     {:order/id 3 :order/account-id 3 :order/asset "SOL"}]
    :open-positions [{:position/account 2 :position/asset "ETH" :position/side :long}]}
   {:working-orders [{:order/id 1 :order/account-id 1 :order/asset "BTC"}
                     {:order/id 2 :order/account-id 2 :order/asset "ETH"}
                     {:order/id 3 :order/account-id 3 :order/asset "SOL"}]
    :open-positions [{:position/account 1 :position/asset "BTC" :position/side :long}
                     {:position/account 3 :position/asset "SOL" :position/side :short}]}])

(deftest db-lookup-returns-trader-and-name
  (let [conn (setup-test-db)]
    (try
      (is (= {:account/trader "florian" :account/name "paper-1"}
             (trader/db-lookup conn 1)))
      (is (nil? (trader/db-lookup conn 999)))
      (finally
        (datahike/db-stop conn)))))

(deftest trader-tagger-flow-tags-each-emission
  (let [conn (setup-test-db)]
    (try
      (let [emissions (m/? (m/reduce conj []
                               (trader/trader-tagger-flow conn (m/seed snapshots))))]
        (is (= 5 (count emissions)))
        (doseq [amended emissions]
          (assert-all-tagged amended)))
      (finally
        (datahike/db-stop conn)))))
