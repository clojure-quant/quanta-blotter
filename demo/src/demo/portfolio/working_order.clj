(ns demo.portfolio.working-order
  (:require
   [clojure.pprint :refer [print-table]]
   [ednx.edn :refer [slurp-edn]]
   [ednx.tick.edn :refer [add-tick-edn-handlers!]]
   [quanta.blotter.oms.portfolio :as portfolio]))

(add-tick-edn-handlers!)

(defn load-channel-paper []
  (slurp-edn "data/channel-paper.edn"))

(defn- order->row
  [{:order/keys [id account-id asset side type status qty qty-filled qty-working avg-price history text date]}]
  {:account account-id
   :order-id id
   :asset asset
   :side side
   :type type
   :status status
   :qty qty
   :qty-filled qty-filled
   :qty-working qty-working
   :avg-price avg-price
   :text text
   :date date
   :# (count history)})

(def ^:private table-cols
  [:account :order-id :asset :side :type :status :qty :qty-filled :qty-working :avg-price :text :#])

(defn- print-orders-table! [orders-by-id]
  (println)
  (if (> (count orders-by-id) 0)
    (print-table table-cols (->> orders-by-id vals (map order->row) (sort-by :order-id)))
    (println "No working orders"))
  (flush))

(defn run-demo!
  "Reads channel-paper.edn; after each channel message prints a table of all orders."
  [_]
  (let [msgs (load-channel-paper)
        closed-orders (atom [])]
    (reduce
     (fn [state msg]
       (let [{:keys [state out-msg]} (portfolio/process-message state msg)]
         (when-let [order (:order-closed out-msg)]
           (swap! closed-orders conj order)
           (println "Closed Orders:")
           (print-table table-cols (->> @closed-orders (map order->row) (sort-by :order-id)))
           (println "\r\n"))
         (when (contains? out-msg :working-order)
           (print-orders-table! (:working-order out-msg)))
         state))
     (portfolio/empty-state)
     msgs)
    nil))

(comment
  (run-demo!))
