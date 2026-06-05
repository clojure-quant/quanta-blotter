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
    (fill-task #(println "fill-order" (:order-id order) " done" %) #(println "fill-order " (:order-id order) " error" %))))

(def reject-reasons
  ["market-closed" "too-many-orders" "temporary-broker-problem"])

(defn reject-reason
  "Returns a reject reason when reject-probability fires, else nil.
   reject-probability 0 always accepts; 1-99 rejects randomly; 100 always rejects."
  [reject-probability]
  (let [p (or reject-probability 0)]
    (when (and (pos? p) (< (rand-int 100) p))
      (rand-nth reject-reasons))))

(defn reject-message
  "Builds a :broker/order-rejected message from a :trader/new-order action."
  [action reason]
  (assoc action
         :type :broker/order-rejected
         :date (t/instant)
         :reason reason
         :message (str "paper broker rejected order: " reason)))

(defn- paper-broker-task [settings pull push log]
  (m/sp
   (log (str "paper broker started " settings))
   (let [orders (atom {})]
     (loop []
       (let [{:keys [type order-id] :as action} (m/? pull)]
         (log (str "paper-broker in: " action))
         (case type
           :trader/new-order
           (if-let [reason (reject-reason (:reject-probability settings))]
             (m/? (push (reject-message action reason)))
             (let [_ (m/? (push (assoc action
                                       :type :broker/order-confirmed
                                       :date (t/instant)
                                       :message "paper broker confirmed new order")))
                   dispose-fill (start-fill! settings log action push)]
               (swap! orders assoc order-id dispose-fill)))

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
                                 :message "paper broker cannot cancel unknown order")))))

           ; else
           (m/? (push {:type :broker/message
                       :message (str "unsupported message type: " type)
                       :order-action action})))
         (recur))))))


(defmethod p/create-trade-account :paper
  [{:keys [account/id account/settings]} pull push log]
  (paper-broker-task settings pull push log))
