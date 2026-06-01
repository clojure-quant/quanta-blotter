(ns quanta.blotter.working-order.wo-cancel-test
  (:require
   [clojure.test :refer :all]
   [missionary.core :as m]
   [quanta.blotter.order-manager.order :refer [order-change-flow
                                      working-orders-flow
                                      current-working-orders]]))

(def order-orderupdate-flow
  (m/seed [{:order-id "123"
            :order {:order-id "123"
                    :asset "BTC"
                    :side :buy
                    :limit 60000.0
                    :qty 0.001}}
           {:order-id "456"
            :order {:order-id "456"
                    :asset "BTC"
                    :side :buy
                    :limit 60000.0
                    :qty 0.001}}
           {:order-id "456"
            :broker-order-status {:order-id "456"
                                  :status :closed
                                  :close-reason "cancel"}}
           {:order-id "789"
            :order {:order-id ""
                    :asset "ETH"
                    :side :buy
                    :limit 60000.0
                    :qty 0.001}}
           {:order-id "789"
            :broker-order-status {:order-id "789"
                                  :status :closed
                                  :close-reason "rejected"}}]))

; (current-working-orders order-orderupdate-flow)
;; => ({:order-status {:status :open, :open-date #inst "2024-07-23T21:09:57.579-00:00", :fill-qty 0.0, :fill-value 0.0},
;;      :open-order {:order-id "123", :asset "BTC", :side :buy, :limit 60000.0, :qty 0.001},
;;      :order-id "123"})

(defn simplify [{:keys [order-id open-order order-status]}]
  (-> order-status
      (assoc :order-id order-id)
      (dissoc :open-date)))

(def working-order-summary
  (->> (current-working-orders order-orderupdate-flow)
       (map simplify)
       ;(map #(select-keys % [:order-id :status :fill-qty]))
       ))

(current-working-orders order-orderupdate-flow)

working-order-summary

;; => ({:status :open, :fill-qty 0.0, :fill-value 0.0, :order-id "123"})

(deftest working-orders-cancelled
  (testing "open-working-orders-cancelled"
    (is (= working-order-summary
           '({:status :open, :fill-qty 0.0, :fill-value 0.0, :order-id "123"})))))


