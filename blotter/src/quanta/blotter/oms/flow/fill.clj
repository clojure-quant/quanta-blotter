(ns quanta.blotter.oms.flow.fill
  (:require
   [missionary.core :as m]))

(defn fill?
  "A broker order-filled message represents a fill."
  [msg]
  (= :broker/order-filled (:type msg)))

(defn ->fill
  "Projects a :broker/order-filled message to a fill record.
   Keeps the keys downstream consumers need:
   - open-positions: :account/id :asset :side :qty :price
   - db persistence:  :fill-id :order-id :date :type"
  [msg]
  {:type :broker/order-filled
   :fill-id (:fill-id msg)
   :order-id (:order-id msg)
   :account/id (:account/id msg)
   :asset (:asset msg)
   :side (:side msg)
   :qty (:qty msg)
   :price (:price msg)
   :date (:date msg)})

(defn fill-flow
  "Consumes a mixed channel flow; emits one fill record per :broker/order-filled
   message."
  [channel-flow]
  (m/eduction
   (filter fill?)
   (map ->fill)
   channel-flow))
