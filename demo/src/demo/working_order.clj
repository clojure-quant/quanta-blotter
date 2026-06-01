(ns demo.working-order
  (:require
   [clojure.pprint :refer [print-table]]
   [ednx.edn :refer [slurp-edn]]
   [ednx.tick.edn :refer [add-tick-edn-handlers!]]
   [missionary.core :as m]
   [quanta.blotter.working-orders :as wo]))

(add-tick-edn-handlers!)

(defn load-channel-paper []
  (slurp-edn "data/channel-paper.edn"))


(defn- order->row
  [{:order/keys [id account asset side status qty qty-filled qty-working avg-price history]}]
  {:account account
   :order-id id
   :asset asset
   :side side
   :status status
   :qty qty
   :qty-filled qty-filled
   :qty-working qty-working
   :avg-price avg-price
   :# (count history)})

(def ^:private table-cols
  [:account :order-id :asset :side :status :qty :qty-filled :qty-working :avg-price :#])

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
        order-change-flow (wo/order-change-flow channel-flow)]
    (m/? (m/reduce
          (fn [orders-by-id order]
            (let [order-id (:order/id order)
                  orders-by-id (if (= :done (:order/status order))
                                 (dissoc orders-by-id order-id)
                                 (assoc orders-by-id order-id order))]
              ;(println "\r\n \r\n" " " order)

              (print-orders-table! orders-by-id)
              orders-by-id
              ))
            {}
            order-change-flow))))

(comment
  (run-demo!)

  )
