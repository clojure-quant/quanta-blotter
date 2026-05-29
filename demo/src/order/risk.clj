(ns demo.order.risk
  (:require
   [missionary.core :as m]
   [quanta.market.protocol :as p]
   [demo.env :refer [pm]]))

(p/snapshot pm)
 ;; => {:working-orders
 ;;     ({:open-order
 ;;       {:account :rene/test4,
 ;;        :asset "BTCUSDT.S",
 ;;        :side :buy,
 ;;        :qty 0.002,
 ;;        :ordertype :market,
 ;;        :order-id "NfEZrKaF",
 ;;        :date-created #inst "2024-07-27T15:17:47.994-00:00"},
 ;;       :order-status {:status :open, :open-date #inst "2024-07-27T15:17:48.060-00:00", :fill-qty 0.0, :fill-value 0.0},
 ;;       :order-id "NfEZrKaF"}),
 ;;     :open-positions {[:rene/test4 "BTCUSDT.S"] 0.002}}

(defn working-order-snapshot [{:keys [open-order order-status order-id]}]
  (assoc open-order
         :fill-qty (:fill-qty order-status)
         :fill-value (:fill-value order-status)))

(defn working-orders-snapshot [working-orders]
  (map working-order-snapshot working-orders))

(defn open-positions-snapshot [working-orders]
  (map (fn [[[account asset] net-qty]]
         {:account account
          :asset asset
          :net-qty net-qty}) working-orders))

(open-positions-snapshot
 {[:rene/test4 "BTCUSDT.S"] 0.002})



