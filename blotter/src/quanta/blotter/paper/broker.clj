(ns quanta.blotter.paper.broker
  (:require
   [missionary.core :as m]
   [quanta.blotter.protocol :as p]
   [quanta.blotter.paper.orderfiller :refer [random-fill-flow]])
  (:import [missionary Cancelled]))

(defn- start-fill! [settings log order push]
  (let [fill-flow (random-fill-flow settings log order)
        fill-send-flow (m/ap (let [fill (m/?> fill-flow)]
                               (m/? (push fill))))
        fill-task (m/reduce identity nil fill-send-flow)]
    (fill-task #(println "fill-order-done") #(println "fill-order-error"))))

(defn- paper-broker-task [settings pull push log]
  (m/sp
   (log (str "paper broker started " settings))
   (let [orders (atom {})]
     (loop []
       (let [{:keys [type order-id] :as action} (m/? pull)]
         (log (str "order-action: " action))
         (case type
           :new-order
           (let [dispose-fill (start-fill! settings log action push)]
             (swap! orders assoc order-id dispose-fill))

           :cancel-order
           (if-let [dispose-fill (get @orders order-id)]
             (do (dispose-fill)
                 (swap! orders dissoc order-id))
             (log (str "cancel ignored, unknown order-id " order-id)))

           (m/? (push {:type :order-update/reject
                       :message (str "unsupported message type: " type)
                       :order-action action})))
         (recur))))))


(defmethod p/create-trade-account :paper
  [{:keys [account/id account/settings]} pull push log]
  (paper-broker-task settings pull push log))
