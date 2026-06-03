(ns quanta.blotter.print
  (:require
   [missionary.core :as m]
   [tick.core :as t]
   [crockery.core :as crockery]
   [quanta.blotter.open-positions :as op]
   [quanta.blotter.working-orders :as wo]
   [quanta.blotter.util :as util]
   [quanta.blotter.logger :as logger]
   [quanta.blotter.validation.flow :as vf]
   ))

(defn working-orders-table [working-orders]
  (with-out-str
    (crockery/print-table
     [{:name :account, :title "account" :align :left :key-fn :order/account}
      {:name :order-id, :title "order-id" :align :left :key-fn :order/id}
      {:name :asset, :align :right :title "asset" :key-fn :order/asset}
      {:name :side, :align :right :title "side" :key-fn :order/side}
      {:name :status, :align :right :title "status" :key-fn :order/status}
      {:name :qty, :align :right :title "qty" :key-fn :order/qty}
      {:name :qty-filled, :align :right :title "qty-filled" :key-fn :order/qty-filled}
      {:name :qty-working, :align :right :title "qty-working" :key-fn :order/qty-working}
      {:name :avg-price, :align :right :title "avg-price" :key-fn :order/avg-price}]
     working-orders)))

(defn open-positions-table [open-positions]
  (with-out-str
    (crockery/print-table
     [{:name :account, :title "account" :align :left :key-fn :position/account}
      {:name :asset, :align :right :title "asset" :key-fn :position/asset}
      {:name :side, :align :right :title "side" :key-fn :position/side}
      {:name :qty, :align :right :title "qty" :key-fn :position/qty}
      {:name :avg-entry, :align :right :title "avg-entry" :key-fn :position/average-entry-price}
      {:name :realized-pl, :align :right :title "realized-pl" :key-fn :position/realized-pl}]
     open-positions)))

(defn- timestamped-table [label table-str]
  (str (t/instant) " " label "\r\n" table-str))

(defn- positions-log-flow
  [channel-flow & [{:keys [method] :or {method :fifo}}]]
  (m/eduction
   (map (fn [positions]
          (timestamped-table "open positions" (open-positions-table positions))))
   (op/open-position-list-flow
    (op/position-change-flow channel-flow {:method method}))))

(defn- working-orders-log-flow [channel-flow]
  (m/eduction
   (map (fn [orders]
          (timestamped-table "working orders" (working-orders-table orders))))
   (wo/working-order-list-flow
    (wo/order-change-flow channel-flow))))

(defn- snapshot-log-flow
  [channel-flow & [{:keys [method] :or {method :fifo}}]]
  (util/mix
   (positions-log-flow channel-flow {:method method})
   (working-orders-log-flow channel-flow)
   (vf/bad-message-with-explaination channel-flow)
   ))

(defn start-open-positions-working-order-logger! [oms log-file]
  (let [channel-flow (get-in oms [:consolidator :combined-flow])
        _ (assert channel-flow "start-open-positions-working-order-logger! needs channel-flow")
        l (logger/create-logger log-file false)
        log-f (snapshot-log-flow channel-flow {:method :fifo})
        dispose! (logger/start-log-flow-to-logger l log-f)]
    {:dispose dispose!}))