(ns quanta.blotter.paper.broker
  (:require
   [missionary.core :as m]
   [tick.core :as t]
   [quanta.blotter.protocol :as p]
   [quanta.blotter.paper.orderfiller :refer [random-fill-flow]])
  (:import [missionary Cancelled]))

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
         :message reason))

(defn bad-orderupdate?
  "Returns true when bad-orderupdate-probability fires."
  [bad-orderupdate-probability]
  (let [p (or bad-orderupdate-probability 0)]
    (< (rand-int 100) p)))

(defn- corrupt-field [msg field value]
  (assoc msg field value))

(defn- drop-field [msg field]
  (dissoc msg field))

(defn corrupt-message
  "Randomly corrupts a broker message so it fails schema validation."
  [msg]
  (let [n (rand-int 3)]
    (case (:type msg)
      :broker/order-filled
      (case n
        0 (drop-field msg :qty)
        1 (corrupt-field msg :qty "not-a-decimal")
        2 (corrupt-field msg :side :long))

      :broker/order-confirmed
      (case n
        0 (drop-field msg :limit)
        1 (corrupt-field msg :qty "not-a-decimal")
        2 (corrupt-field msg :side :long))

      :broker/order-rejected
      (case n
        0 (drop-field msg :order-id)
        1 (corrupt-field msg :account/id "not-an-int")
        2 (corrupt-field msg :message 123))

      :broker/cancel-confirmed
      (case n
        0 (drop-field msg :account/id)
        1 (corrupt-field msg :order-id "not-an-id")
        2 (corrupt-field msg :message 123))

      :broker/cancel-rejected
      (case n
        0 (drop-field msg :order-id)
        1 (corrupt-field msg :account/id "not-an-int")
        2 (corrupt-field msg :message 123))

      :broker/order-canceled
      (case n
        0 (drop-field msg :date)
        1 (corrupt-field msg :order-id "not-an-id")
        2 (corrupt-field msg :account/id "not-an-int"))

      msg)))

(defn- push-update [settings push msg]
  (push (if (bad-orderupdate? (:bad-orderupdate-probability settings))
          (corrupt-message msg)
          msg)))

(defn- start-fill! [settings log order push]
  (let [fill-flow (random-fill-flow settings log order)
        fill-send-flow (m/ap (let [fill (m/?> fill-flow)]
                               (m/? (push-update settings push fill))))
        fill-task (m/reduce (fn [r v] nil) nil fill-send-flow)]
    (fill-task #(println "fill-order" (:order-id order) " done" %) #(println "fill-order " (:order-id order) " error" %))))

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
             (m/? (push-update settings push (reject-message action reason)))
             (let [_ (m/? (push-update settings push (assoc action
                                                             :type :broker/order-confirmed
                                                             :date (t/instant)
                                                             :message "paper broker confirmed new order")))
                   dispose-fill (start-fill! settings log action push)]
               (swap! orders assoc order-id dispose-fill)))

           :trader/cancel-order
           (if-let [dispose-fill (get @orders order-id)]
             (do (m/? (push-update settings push (assoc action
                                                         :type :broker/cancel-confirmed
                                                         :message "paper broker confirmed order canceled received.")))
                 (dispose-fill)
                 (swap! orders dissoc order-id))
             (do
               (log (str "cancel ignored, unknown order-id " order-id))
               (m/? (push-update settings push (assoc action
                                                       :type :broker/cancel-rejected
                                                       :message "paper broker cannot cancel unknown order")))))

           ; else
           (m/? (push-update settings push {:type :broker/message
                                            :message (str "unsupported message type: " type)
                                            :order-action action})))
         (recur))))))


(defmethod p/create-trade-account :paper
  [{:keys [account/id account/settings]} pull push log]
  (paper-broker-task settings pull push log))
