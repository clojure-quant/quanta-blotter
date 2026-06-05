(ns quanta.blotter.oms.print
  (:require
   [tick.core :as t]
   [crockery.core :as crockery]))

(defn working-orders-table [working-orders]
  (with-out-str
    (crockery/print-table
     [{:name :date, :align :left :title "date" :key-fn :order/date}
      {:name :account, :title "account" :align :left :key-fn :order/account-id}
      {:name :order-id, :title "order-id" :align :left :key-fn :order/id}
      {:name :asset, :align :right :title "asset" :key-fn :order/asset}
      {:name :side, :align :right :title "side" :key-fn :order/side}
      {:name :qty, :align :right :title "qty" :key-fn :order/qty}
      {:name :type, :align :right :title "type" :key-fn :order/type}
      {:name :status, :align :right :title "status" :key-fn :order/status}
      {:name :qty-working, :align :right :title "qty-working" :key-fn :order/qty-working}
      {:name :qty-filled, :align :right :title "qty-filled" :key-fn :order/qty-filled}
      {:name :avg-price, :align :right :title "avg-price" :key-fn :order/avg-price}
      {:name :text, :align :right :title "text" :key-fn :order/text}]
     working-orders)))


(defn trades-table [trades]
  (with-out-str
    (crockery/print-table
     [{:name :date, :align :left :title "date" :key-fn :fill/date}
      {:name :account, :title "account" :align :left :key-fn :fill/account-id}
      {:name :order-id, :title "order-id" :align :left :key-fn :fill/order-id}
      {:name :asset, :align :right :title "asset" :key-fn :fill/asset}
      {:name :side, :align :right :title "side" :key-fn :fill/side}
      {:name :qty, :align :right :title "qty" :key-fn :fill/qty}
      {:name :price, :align :right :title "price" :key-fn :fill/price}
      {:name :fill-id, :align :right :title "fill-id" :key-fn :fill/id}]
     trades)))


(defn open-positions-table [open-positions]
  (with-out-str
    (crockery/print-table
     [{:name :date-opened, :align :left :title "date-opened" :key-fn :position/date-open}
      {:name :account, :title "account" :align :left :key-fn :position/account}
      {:name :asset, :align :right :title "asset" :key-fn :position/asset}
      {:name :side, :align :right :title "side" :key-fn :position/side}
      {:name :open, :align :right :title "open" :key-fn :position/open}
      {:name :qty-open, :align :right :title "qty-open" :key-fn :position/qty-open}
      {:name :qty, :align :right :title "qty-max" :key-fn :position/qty}
      {:name :avg-entry, :align :right :title "avg-entry" :key-fn :position/average-entry-price}
      {:name :avg-exit, :align :right :title "avg-exit" :key-fn :position/avg-exit-price}
      {:name :realized-pl, :align :right :title "realized-pl" :key-fn :position/realized-pl}]
     open-positions)))

(defn timestamped-table [label table-str]
  (str (t/instant) " " label "\r\n" table-str))
