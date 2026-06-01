(ns quanta.blotter.working-order.orderupdate-unknown-order-test
  (:require
   [clojure.test :refer :all]
   [missionary.core :as m]
   [quanta.blotter.order-manager.order :refer [order-change-flow]]))

;; broker sends update to an unkown order.
;; we ignore it and alert the trader.

(def order-orderupdate-flow
  (m/seed [{:order-id "456"
            :broker-order-status {:order-id "456"
                                  :status :open
                                  :fill-qty 0.05
                                  :fill-value 200.0}}]))

(def changes
  (m/? (m/reduce conj [] (order-change-flow order-orderupdate-flow))))

changes
;; => [["456" {}]
;;     ["456"
;;      {:transactions
;;       {:alert
;;        {:text "ignoring order-update to unknown order!",
;;         :data
;;         {:working-order {},
;;          :msg
;;          {:order-id "456", :broker-order-status {:order-id "456", :status :open, :fill-qty 0.05, :fill-value 200.0}}}}}}]]

(deftest orderstatus-unknown-order
  (testing "orderstatus for unknown order"
    (is (= changes
           [["456" {}]
            ["456"
             {:transactions
              {:alert
               {:text "ignoring order-update to unknown order!",
                :data
                {:working-order {},
                 :msg
                 {:order-id "456", :broker-order-status {:order-id "456", :status :open, :fill-qty 0.05,
                                                         :fill-value 200.0}}}}}}]]))))

