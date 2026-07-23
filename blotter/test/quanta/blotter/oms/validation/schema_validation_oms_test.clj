(ns quanta.blotter.oms.validation.schema-validation-oms-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [tick.core :as t]
   [quanta.blotter.account-manager :refer [add-account]]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager! stop-order-manager!
                                    combined-flow]]
   [quanta.market-sim.broker-paper]
   [quanta.quote.core :as qc]))

(def account-id 3)

(def valid-new-order
  {:type :trader/new-order
   :account/id account-id
   :order-id 1
   :asset "BTCUSDT"
   :side :buy
   :order-type :limit
   :limit 100.0M
   :qty 0.001M})

(defn- bad-order [base]
  (dissoc base :qty))

(def original-orders
  [valid-new-order (bad-order (assoc valid-new-order :order-id 2))])

(defn- temp-file [prefix]
  (.getPath (java.io.File/createTempFile prefix ".txt")))

(defn- start-combined-collector! [oms events]
  ((m/reduce (fn [_ v] (swap! events conj v)) nil (combined-flow oms))
   (fn [_] nil)
   (fn [_] nil)))

(deftest schema-validation-oms-test
  (with-redefs [qc/asset-quote-flow
                (fn [_ _]
                  (m/seed (repeatedly 20 (fn []
                                           {:asset "BTCUSDT"
                                            :bid 100.0M
                                            :ask 100.01M
                                            :ts (t/instant)}))))]
    (let [tx-file (temp-file "oms-validation-tx-")
          oms (create-order-manager {:transaction-log-file tx-file
                                     :validate? true
                                     :tag? false
                                     :ctx {:quote-manager ::test-quote-manager}})
          combined-events (atom [])]
      (try
        (start-order-manager! oms) ; returns oms with :trading-state
        (start-combined-collector! oms combined-events)
        (add-account (:account-manager oms)
                     {:account/id account-id
                      :account/api :paper
                      :account/settings {:reject-probability 0
                                         :bad-orderupdate-probability 100
                                         :fill-probability 100
                                         :fill-qty-prct [100]
                                         :ms-between-fills 0}})
        (testing "invalid order is rejected with :broker/order-rejected"
          (let [bad (second original-orders)
                order-rdv (get-in oms [:internal :order-rdv])]
            (m/? (m/sp
                  (m/? (order-rdv bad))
                  (m/? (m/sleep 50))))
            (is (some #(and (= :broker/order-rejected (:type %))
                            (re-find #"^spec-error" (:message %)))
                      @combined-events)
                "order spec rejection appears on combined channel")))
        (testing "corrupted broker orderupdate emits :broker/orderupdate-schema-error"
          (let [order-rdv (get-in oms [:internal :order-rdv])]
            (m/? (m/sp
                  (m/? (order-rdv valid-new-order))
                  (m/? (m/sleep 750))))
            (is (some #(and (= :broker/orderupdate-schema-error (:type %))
                            (re-find #"^spec-error" (:message %)))
                      @combined-events)
                "corrupted orderupdates appear as :broker/orderupdate-schema-error on combined channel")))
        (finally
          (stop-order-manager! oms))))))
