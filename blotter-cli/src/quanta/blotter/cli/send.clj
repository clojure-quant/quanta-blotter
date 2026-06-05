(ns quanta.blotter.cli.send
  "One-shot order sender: connect, submit every order in an orderlist edn file
   over the flowy websocket, then print the submitted orders (with the
   order-ids assigned by the OMS) as a single table and exit."
  (:require
   [clojure.edn :as edn]
   [crockery.core :as crockery]
   [quanta.blotter.cli.client :as client]))

(def create-order-fn 'quanta.blotter.oms.core/create-limit-order)

(defn- read-orderlist
  "Read orderlist/<name>.edn (relative to the current working directory)."
  [name]
  (let [path (str "orderlist/" name ".edn")]
    (edn/read-string (slurp path))))

(defn- print-sent-orders-table
  "Print the submitted orders (order-id + full order data) in one table."
  [orders]
  (crockery/print-table
   [{:name :order-id :title "order-id" :align :left :key-fn :order-id}
    {:name :account :title "account" :align :right :key-fn :account/id}
    {:name :asset :title "asset" :align :right :key-fn :asset}
    {:name :side :title "side" :align :right :key-fn :side}
    {:name :qty :title "qty" :align :right :key-fn :qty}
    {:name :limit :title "limit" :align :right :key-fn :limit}
    {:name :type :title "type" :align :right :key-fn :type}]
   orders))

(defn send-orders!
  "Connect, submit every order from orderlist/<name>.edn, disconnect, and print
   the submitted orders. `name` is the orderlist file name without extension."
  ([name] (send-orders! name "ws://localhost:9000/flowy"))
  ([name ws-url]
   (when (nil? name)
     (throw (ex-info "usage: bb send-orders <orderlist-name>  (e.g. fx1)" {})))
   (let [orders (read-orderlist name)
         conn (client/connect! ws-url)]
     ;; give the websocket a moment to finish the handshake.
     (Thread/sleep 500)
     (try
       (let [sent (mapv (fn [o]
                          (client/request-sync! conn create-order-fn [o]))
                        orders)]
         (println)
         (println (count sent) "orders sent from orderlist/" (str name ".edn"))
         (println)
         (print-sent-orders-table sent)
         sent)
       (finally
         (client/close! conn))))))
