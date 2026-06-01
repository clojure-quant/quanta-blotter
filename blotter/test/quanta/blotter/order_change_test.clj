(ns quanta.blotter.order-change-test
  (:require
   [clojure.test :refer :all]
   [missionary.core :as m]
   [quanta.blotter.order-manager.order :refer [order-change-flow]]))

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



(m/? (m/reduce conj [] (order-change-flow order-orderupdate-flow)))

[["123" {}]
 ["456" {}]
 ["123"
  {:open-order {:order-id "123", :asset "BTC", :side :buy, :limit 60000.0, :qty 0.001},
   :order-status {:status :open, :open-date #inst "2026-05-30T16:08:52.867-00:00", :fill-qty 0.0, :fill-value 0.0},
   :transactions #:order{:created {:order-id "123", :asset "BTC", :side :buy, :limit 60000.0, :qty 0.001}}}]
 ["456"
  {:open-order {:order-id "456", :asset "BTC", :side :buy, :limit 60000.0, :qty 0.001},
   :order-status {:status :open, :open-date #inst "2026-05-30T16:08:52.867-00:00", :fill-qty 0.0, :fill-value 0.0},
   :transactions #:order{:created {:order-id "456", :asset "BTC", :side :buy, :limit 60000.0, :qty 0.001}}}]
 ["789" {}]
 ["456"
  {:open-order {:order-id "456", :asset "BTC", :side :buy, :limit 60000.0, :qty 0.001},
   :order-status
   {:status :closed,
    :open-date #inst "2026-05-30T16:08:52.867-00:00",
    :fill-qty 0.0,
    :fill-value 0.0,
    :close-reason "cancel",
    :close-date #inst "2026-05-30T16:08:52.867-00:00"},
   :transactions
   #:order{:close {:status :closed, :close-reason "cancel", :close-date #inst "2026-05-30T16:08:52.867-00:00"}}}]
 ["789"
  {:open-order {:order-id "", :asset "ETH", :side :buy, :limit 60000.0, :qty 0.001},
   :order-status {:status :open, :open-date #inst "2026-05-30T16:08:52.867-00:00", :fill-qty 0.0, :fill-value 0.0},
   :transactions #:order{:created {:order-id "", :asset "ETH", :side :buy, :limit 60000.0, :qty 0.001}}}]
 ["789"
  {:open-order {:order-id "", :asset "ETH", :side :buy, :limit 60000.0, :qty 0.001},
   :order-status
   {:status :closed,
    :open-date #inst "2026-05-30T16:08:52.867-00:00",
    :fill-qty 0.0,
    :fill-value 0.0,
    :close-reason "rejected",
    :close-date #inst "2026-05-30T16:08:52.867-00:00"},
   :transactions
   #:order{:close {:status :closed, :close-reason "rejected", :close-date #inst "2026-05-30T16:08:52.867-00:00"}}}]]