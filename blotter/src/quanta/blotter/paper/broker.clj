(ns quanta.blotter.paper.broker
  (:require
   [missionary.core :as m]
   [tick.core :as t]
   [quanta.blotter.protocol :as p]
   [quanta.blotter.paper.orderfiller :refer [random-fill-flow]])
  (:import [missionary Cancelled]))

(defn- start-fill! [settings log order push]
  (let [fill-flow (random-fill-flow settings log order)
        fill-send-flow (m/ap (let [fill (m/?> fill-flow)]
                               (m/? (push fill))))
        fill-task (m/reduce (fn [r v] nil) nil fill-send-flow)]
    (fill-task #(println "fill-order-done" %) #(println "fill-order-error" %))))

(defn- paper-broker-task [settings pull push log]
  (m/sp
   (log (str "paper broker started " settings))
   (let [orders (atom {})]
     (loop []
       (let [{:keys [type order-id] :as action} (m/? pull)]
         (log (str "paper-broker in: " action))
         (case type
           :trader/new-order
           (let [_ (m/? (push (assoc action 
                                     :type :broker/order-confirmed
                                     :date (t/instant)
                                     :message "paper broker confirmed new order")))
                 dispose-fill (start-fill! settings log action push)]
             (swap! orders assoc order-id dispose-fill))

           :trader/cancel-order
           (if-let [dispose-fill (get @orders order-id)]
             (do (m/? (push (assoc action
                                   :type :broker/cancel-confirmed
                                   :message "paper broker confirmed order canceled received.")))
                 (dispose-fill)
                 (swap! orders dissoc order-id))
             (do 
               (log (str "cancel ignored, unknown order-id " order-id))  
               (m/? (push (assoc action
                                 :type :broker/cancel-rejected
                                 :message "paper broker cannot cancel unkonw order")))))

           ; else
           (m/? (push {:type :broker/message
                       :message (str "unsupported message type: " type)
                       :order-action action})))
         (recur))))))


(defmethod p/create-trade-account :paper
  [{:keys [account/id account/settings]} pull push log]
  (paper-broker-task settings pull push log))
