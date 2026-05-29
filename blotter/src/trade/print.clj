(ns quanta.market.trade.print
  (:require
   [crockery.core :as crockery]))

(defn working-orders-table [working-orders]
  (with-out-str
    (crockery/print-table
     [{:name :account2, :title "account" :align :left :key-fn #(get-in % [:open-order :account])}
      {:name :order-id2, :title "order-id" :align :left :key-fn #(get-in % [:open-order :order-id])}
      {:name :asset, :align :right :title "asset" :key-fn #(get-in % [:open-order :asset])}
      {:name :asset, :align :right :title "side" :key-fn #(get-in % [:open-order :side])}
      {:name :asset, :align :right :title "qty" :key-fn #(get-in % [:open-order :qty])}
      {:name :order-type2, :title "otype" :align :left :key-fn #(get-in % [:open-order :ordertype])}
      {:name :order-type3, :title "limit" :align :left :key-fn #(get-in % [:open-order :limit])}
      {:name :asset, :align :right :title "fill-qty" :key-fn #(get-in % [:order-status :fill-qty])}
      {:name :asset, :align :right :title "fill-value" :key-fn #(get-in % [:order-status :fill-value])}]
     working-orders)))

(defn open-positions-table [open-positions]
  (with-out-str
    (crockery/print-table
     [{:name :account2, :title "account" :align :left :key-fn :account}
      {:name :asset, :align :right :title "asset" :key-fn :asset}
      {:name :asset, :align :right :title "net-qty" :key-fn :net-qty}]
     open-positions)))