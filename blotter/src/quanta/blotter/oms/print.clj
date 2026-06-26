(ns quanta.blotter.oms.print
  (:require
   [tick.core :as t]
   [crockery.core :as crockery]))

(def default-table-max-width
  "Avoid crockery terminal-width rebalancing, which can shrink columns below
   ellipsis-safe widths and crash on narrow ttys."
  170)

(defn- table-opts [{:keys [max-width] :as opts}]
  (merge {:max-width (or max-width default-table-max-width)} opts))

(defn working-orders-table
  ([working-orders]
   (working-orders-table working-orders {}))
  ([working-orders opts]
   (with-out-str
     (crockery/print-table
      (table-opts opts)
      [{:name :date, :align :left :title "date" :key-fn :order/date}
       {:name :account, :title "account" :align :left :key-fn :order/account-id}
       {:name :campaign, :title "campaign" :align :left :key-fn :order/campaign}
       {:name :label, :title "label" :align :left :key-fn :order/label}
       {:name :order-id, :title "order-id" :align :left :key-fn :order/id}
       {:name :asset, :align :right :title "asset" :key-fn :order/asset}
       {:name :side, :align :right :title "side" :key-fn :order/side}
       {:name :qty, :align :right :title "qty" :key-fn :order/qty}
       {:name :type, :align :right :title "type" :key-fn :order/type}
       {:name :limit, :align :right :title "limit" :key-fn :order/limit}
       {:name :status, :align :right :title "status" :key-fn :order/status}
       {:name :qty-working, :align :right :title "qty-working" :key-fn :order/qty-working}
       {:name :qty-filled, :align :right :title "qty-filled" :key-fn :order/qty-filled}
       {:name :avg-price, :align :right :title "avg-price" :key-fn :order/avg-price}
       {:name :text, :align :right :title "text" :key-fn :order/text}]
      working-orders))))

(defn trades-table
  ([trades]
   (trades-table trades {}))
  ([trades opts]
   (with-out-str
     (crockery/print-table
      (table-opts opts)
      [{:name :date, :align :left :title "date" :key-fn :fill/date}
       {:name :account, :title "account" :align :left :key-fn :fill/account-id}
       {:name :campaign, :title "campaign" :align :left :key-fn :fill/campaign}
       {:name :label, :title "label" :align :left :key-fn :fill/label}
       {:name :order-id, :title "order-id" :align :left :key-fn :fill/order-id}
       {:name :asset, :align :right :title "asset" :key-fn :fill/asset}
       {:name :side, :align :right :title "side" :key-fn :fill/side}
       {:name :qty, :align :right :title "qty" :key-fn :fill/qty}
       {:name :price, :align :right :title "price" :key-fn :fill/price}
       {:name :fill-id, :align :right :title "fill-id" :key-fn :fill/id}]
      trades))))

(defn open-positions-table
  ([open-positions]
   (open-positions-table open-positions {}))
  ([open-positions opts]
   (with-out-str
     (crockery/print-table
      (table-opts opts)
      [{:name :date-open, :align :left :title "date-open" :key-fn :position/date-open}
       {:name :account, :title "account" :align :left :key-fn :position/account}
       {:name :asset, :align :right :title "asset" :key-fn :position/asset}
       {:name :side, :align :right :title "side" :key-fn :position/side}
       {:name :open, :align :right :title "open" :key-fn :position/open}
       {:name :qty-open, :align :right :title "qty-open" :key-fn :position/qty-open}
       {:name :qty, :align :right :title "qty-max" :key-fn :position/qty}
       {:name :avg-entry, :align :right :title "avg-entry" :key-fn :position/average-entry-price}
       {:name :avg-exit, :align :right :title "avg-exit" :key-fn :position/avg-exit-price}
       {:name :realized-pl, :align :right :title "realized-pl" :key-fn :position/realized-pl}
       {:name :date-close, :align :left :title "date-close" :key-fn :position/date-close}]
      open-positions))))

(defn timestamped-table [label table-str]
  (str (t/instant) " " label "\r\n" table-str))

(defn trader-requests-table
  ([trader-requests]
   (trader-requests-table trader-requests {}))
  ([trader-requests opts]
   (with-out-str
     (crockery/print-table
      (table-opts opts)
      [{:name :date, :align :left :title "date" :key-fn :date}
       {:name :account, :title "account" :align :left :key-fn :account/id}
       {:name :campaign, :title "campaign" :align :left :key-fn :campaign}
       {:name :label, :title "label" :align :left :key-fn :label}
       {:name :order-id, :title "order-id" :align :left :key-fn :order-id}
       {:name :type, :align :left :title "msg-type" :key-fn :type}
       {:name :asset, :align :right :title "asset" :key-fn :asset}
       {:name :side, :align :right :title "side" :key-fn :side}
       {:name :qty, :align :right :title "qty" :key-fn :qty}
       {:name :order-type, :align :right :title "order-type" :key-fn :order-type}
       {:name :limit, :align :right :title "limit" :key-fn :limit}
       ]
      trader-requests))))