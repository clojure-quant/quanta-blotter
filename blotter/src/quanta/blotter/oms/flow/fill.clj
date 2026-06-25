(ns quanta.blotter.oms.flow.fill
  (:require
   [missionary.core :as m]))

(defn fill?
  "A broker order-filled message represents a fill."
  [msg]
  (= :broker/order-filled (:type msg)))

(defn ->fill
  "Projects a :broker/order-filled message to a db-shaped fill record."
  [msg]
  (println "fill orderupdate: " msg)
  (cond-> {:fill/id (:fill-id msg)
           :fill/order-id (:order-id msg)
           :fill/account-id (:account/id msg)
           :fill/asset (:asset msg)
           :fill/side (:side msg)
           :fill/qty (some-> (:qty msg) bigdec)
           :fill/price (some-> (:price msg) bigdec)
           :fill/date (:date msg)}
    (:campaign msg) (assoc :fill/campaign (:campaign msg))
    (:label msg) (assoc :fill/label (:label msg))))

(defn fill-flow
  "Consumes a mixed channel flow; emits one fill record per :broker/order-filled
   message."
  [channel-flow]
  (m/eduction
   (filter fill?)
   (map ->fill)
   channel-flow))
