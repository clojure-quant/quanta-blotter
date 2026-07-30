(ns quanta.blotter.oms.portfolio-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [quanta.blotter.oms.portfolio :as portfolio]))

(def working-orders-snapshot
  {:type :broker/working-orders
   :account/id 1000
   :req-id "orders-1"
   :date #inst "2026-07-30T20:00:00.000Z"
   :orders [{:order/id "order-1"}]})

(def open-positions-snapshot
  {:type :broker/open-positions
   :account/id 1000
   :req-id "positions-1"
   :date #inst "2026-07-30T20:00:00.000Z"
   :positions [{:position/position-id "position-1"}]})

(deftest broker-snapshots-are-forwarded-without-changing-state
  (doseq [[out-key snapshot] [[:broker/working-orders working-orders-snapshot]
                              [:broker/open-positions open-positions-snapshot]]]
    (testing (name out-key)
      (let [state (portfolio/empty-state)
            result (portfolio/process-message state snapshot)]
        (is (= state (:state result)))
        (is (= snapshot (get-in result [:out-msg :msg])))
        (is (= snapshot (get-in result [:out-msg out-key])))))))

(deftest ordinary-messages-do-not-gain-broker-snapshot-keys
  (let [msg {:type :heartbeat}
        out-msg (:out-msg (portfolio/process-message (portfolio/empty-state) msg))]
    (is (= msg (:msg out-msg)))
    (is (not (contains? out-msg :broker/working-orders)))
    (is (not (contains? out-msg :broker/open-positions)))))
