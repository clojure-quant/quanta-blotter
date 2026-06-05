(ns quanta.blotter.cli.send
  "One-shot order sender: connect, submit every order in an orderlist edn file
   over the flowy websocket, then print the submitted orders (with the
   order-ids assigned by the OMS) as a single table and exit."
  (:require
   [clojure.edn :as edn]
   [crockery.core :as crockery]
   [quanta.blotter.cli.client :as client]))

(def create-order-fn 'quanta.blotter.oms.core/create-limit-order)

(def ^:private orderlist-dir "orderlist")

(defn- list-orderlists
  "Return names of orderlist/<name>.edn files (without extension), sorted."
  []
  (let [dir (java.io.File. orderlist-dir)]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
           (filter #(.isFile %))
           (map #(.getName %))
           (filter #(.endsWith % ".edn"))
           (map #(subs % 0 (- (count %) 4)))
           sort
           vec)
      [])))

(defn- print-orderlist-usage []
  (println "usage: bb send-orders <orderlist-name>")
  (println)
  (let [names (list-orderlists)]
    (if (seq names)
      (do
        (println "available orderlists:")
        (doseq [n names]
          (println " " n)))
      (println "no orderlists found in" orderlist-dir "/"))))

(defn- read-orderlist
  "Read orderlist/<name>.edn (relative to the current working directory)."
  [name]
  (let [path (str orderlist-dir "/" name ".edn")]
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
     (print-orderlist-usage)
     (System/exit 0))
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
