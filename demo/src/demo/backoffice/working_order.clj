(ns demo.backoffice.working-order
  (:require
   [clojure.pprint :refer [print-table]]
   [ednx.edn :refer [slurp-edn]]
   [ednx.tick.edn :refer [add-tick-edn-handlers!]]
   [missionary.core :as m]
   [quanta.blotter.oms.flow.working-orders :as wo]))

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
  []
  (let [channel-flow (m/seed (load-channel-paper))
        order-change-flow (wo/order-change-flow channel-flow)
        closed-orders (atom [])]
    (m/? (m/reduce
          (fn [orders-by-id order]
            (let [order-id (:order/id order)
                  orders-by-id (if (contains? wo/closed-statuses (:order/status order))
                                 (do (swap! closed-orders conj order)
                                     (println "Closed Orders:")
                                     (print-table table-cols (->> @closed-orders (map order->row) (sort-by :order-id)))
                                     (println "\r\n")
                                     (dissoc orders-by-id order-id))
                                 (assoc orders-by-id order-id order))]
              ;(println "\r\n \r\n" " " order)

              (print-orders-table! orders-by-id)
              orders-by-id))
          {}
          order-change-flow))))

(comment
  (run-demo!))
