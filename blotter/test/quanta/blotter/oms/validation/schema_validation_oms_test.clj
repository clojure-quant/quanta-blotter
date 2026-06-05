(ns quanta.blotter.oms.validation.schema-validation-oms-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [quanta.blotter.account-manager :refer [add-account]]
   [quanta.blotter.oms.core :refer [create-order-manager start-order-manager! stop-order-manager!
                                    combined-flow]]
   [quanta.blotter.paper.broker]))

(def account-id 3)

(def valid-new-order
  {:type :trader/new-order
   :account/id account-id
   :order-id 1
   :asset "BTCUSDT"
   :side :buy
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
  (let [log-file (temp-file "oms-validation-log-")
        tx-file (temp-file "oms-validation-tx-")
        oms (create-order-manager {:log-file log-file
                                   :transaction-log-file tx-file
                                   :validate? true})
        combined-events (atom [])]
    (try
      (start-order-manager! oms)
      (start-combined-collector! oms combined-events)
      (add-account (:account-manager oms)
                   {:account/id account-id
                    :account/api :paper
                    :account/settings {:reject-probability 0
                                       :bad-orderupdate-probability 100
                                       :fill-probability 100
                                       :fill-qty-prct [100]
                                       :wait-seconds 0}})
      (testing "invalid order is rejected with spec-error on orderupdate channel"
        (let [bad (second original-orders)]
          (m/? (m/sp
                (m/? ((:order-rdv oms) bad))
                (m/? (m/sleep 50))))
          (is (some #(and (= :broker/order-rejected (:type %))
                          (re-find #"^spec-error" (:message %)))
                    @combined-events)
              "order spec rejection appears on combined channel")))
      (testing "corrupted broker orderupdate is logged as schema/error"
        (m/? (m/sp
              (m/? ((:order-rdv oms) valid-new-order))
              (m/? (m/sleep 750))))
        (let [log-text (when (.exists (java.io.File. log-file)) (slurp log-file))]
          (is (re-find #":schema/error" log-text)
              "failed orderupdate produces schema/error log entry")
          (is (re-find #":original-msg" log-text))))
      (finally
        (stop-order-manager! oms)))))
