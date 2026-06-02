(ns quanta.blotter.print
  (:require
   [crockery.core :as crockery]
   [tick.core :as t]
   [quanta.blotter.open-positions :as op]
   [quanta.blotter.working-orders :as wo]
   [missionary.core :as m]
   [quanta.blotter.logger :as logger]))

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
     [{:name :account, :title "account" :align :left :key-fn :position/account}
      {:name :asset, :align :right :title "asset" :key-fn :position/asset}
      {:name :side, :align :right :title "side" :key-fn :position/side}
      {:name :qty, :align :right :title "qty" :key-fn :position/qty}
      {:name :avg-entry, :align :right :title "avg-entry" :key-fn :position/average-entry-price}
      {:name :realized-pl, :align :right :title "realized-pl" :key-fn :position/realized-pl}]
     open-positions)))

(defn timestamped-table [open-positions]
  (str (t/instant) " open positions \r\n" (open-positions-table open-positions)))

(defn start-open-positions-working-order-logger! [oms log-file]
  (let [channel-flow (get-in oms [:consolidator :combined-flow])
        _ (assert channel-flow "start-open-positions-working-order-logger! needs channel-flow")
        l (logger/create-logger log-file false)
        pos-change-f (op/position-change-flow channel-flow {:method :fifo})
        open-pos-list-f (op/open-position-list-flow pos-change-f)
        timestamped-pos-f (m/eduction (map timestamped-table) open-pos-list-f)
        ;working-order-f (wo/working-order-flow channel-flow)

        dispose1! (logger/start-log-flow-to-logger l 
                                                   
                                                   timestamped-pos-f
                                                   ;open-pos-list-f
                                                   ;pos-change-f
                                                   )
        ]
    {:dispose1 dispose1!}))