(ns quanta.blotter.oms.print
  (:require
   [tick.core :as t]
   [crockery.core :as crockery]))

(defn working-orders-table [working-orders]
  (with-out-str
    (crockery/print-table
     [{:name :account, :title "account" :align :left :key-fn :order/account}
      {:name :order-id, :title "order-id" :align :left :key-fn :order/id}
      {:name :asset, :align :right :title "asset" :key-fn :order/asset}
      {:name :side, :align :right :title "side" :key-fn :order/side}
      {:name :qty, :align :right :title "qty" :key-fn :order/qty}
      {:name :type, :align :right :title "type" :key-fn :order/type}
      {:name :status, :align :right :title "status" :key-fn :order/status}
      {:name :qty-working, :align :right :title "qty-working" :key-fn :order/qty-working}
      {:name :qty-filled, :align :right :title "qty-filled" :key-fn :order/qty-filled}
      {:name :avg-price, :align :right :title "avg-price" :key-fn :order/avg-price}]
     working-orders)))


(defn trades-table [trades]
  (with-out-str
    (crockery/print-table
     [{:name :account, :title "account" :align :left :key-fn :fill/account-id}
      {:name :order-id, :title "order-id" :align :left :key-fn :fill/order-id}
      {:name :asset, :align :right :title "asset" :key-fn :fill/asset}
      {:name :side, :align :right :title "side" :key-fn :fill/side}
      {:name :qty, :align :right :title "qty" :key-fn :fill/qty}
      {:name :price, :align :right :title "price" :key-fn :fill/price}
      {:name :date, :align :right :title "date" :key-fn :fill/date}
      {:name :fill-id, :align :right :title "fill-id" :key-fn :fill/id}]
     trades)))


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

(defn timestamped-table [label table-str]
  (str (t/instant) " " label "\r\n" table-str))
