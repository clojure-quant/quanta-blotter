(ns quanta.market-sim.broker-paper
  (:require
   [missionary.core :as m]
   [taoensso.timbre :refer [info]]
   [tick.core :as t]
   [quanta.blotter.protocol :as p]
   [quanta.market-sim.broker-paper.orderfiller :refer [simulated-fill-flow]])
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

      :broker/order-modified
      (case n
        0 (drop-field msg :asset)
        1 (corrupt-field msg :qty "not-a-decimal")
        2 (corrupt-field msg :account/id "not-an-int"))

      :broker/modify-rejected
      (case n
        0 (drop-field msg :order-id)
        1 (corrupt-field msg :account/id "not-an-int")
        2 (corrupt-field msg :message 123))

      msg)))

(defn- push-update [settings push msg]
  (push (if (bad-orderupdate? (:bad-orderupdate-probability settings))
          (corrupt-message msg)
          msg)))

(defn- start-fill! [ctx settings log order push]
  (let [fill-flow (simulated-fill-flow ctx settings log order)
        fill-send-flow (m/ap (let [fill (m/?> fill-flow)]
                               (m/? (push-update settings push fill))))
        fill-task (m/reduce (fn [r v] nil) nil fill-send-flow)]
    (fill-task #(info "[paper-broker] fill-order-task" (:order-id order) "done" %)
               #(info "[paper-broker] fill-order-task" (:order-id order) "error" %))))

(defn- order-confirmed [action]
  (cond-> {:type :broker/order-confirmed
           :account/id (:account/id action)
           :order-id (:order-id action)
           :asset (:asset action)
           :side (:side action)
           :qty (:qty action)
           :order-type (:order-type action)
           :date (t/instant)}
    (#{:limit :stop} (:order-type action)) (assoc :limit (:limit action))
    (some? (:campaign action)) (assoc :campaign (:campaign action))
    (some? (:label action)) (assoc :label (:label action))))

(defn- paper-broker-task [ctx settings pull push log]
  (m/sp
   (log {:paper/started settings})
   (let [orders (atom {})]
     (loop []
       (let [{:keys [type order-id] :as action} (m/? pull)]
         (log action)
         (case type
           :trader/new-order
           (if-let [reason (reject-reason (:reject-probability settings))]
             (let [rejected (reject-message action reason)]
               (log rejected)
               (m/? (push-update settings push rejected)))
             (let [confirmed (order-confirmed action)
                   _ (log confirmed)
                   _ (m/? (push-update settings push confirmed))
                   dispose-fill (start-fill! ctx settings log action push)]
               (swap! orders assoc order-id {:dispose dispose-fill
                                             :order-details action})))

           :trader/cancel-order
           (if-let [{:keys [dispose]} (get @orders order-id)]
             (do (dispose)
                 (swap! orders dissoc order-id)
                 (m/? (push-update settings push (assoc action
                                                        :type :broker/cancel-confirmed))))
             (do
               (log {:paper/cancel-reject (str "cancel-rejected, unknown order-id " order-id)})
               (m/? (push-update settings push (assoc action
                                                      :type :broker/cancel-rejected
                                                      :message "unknown order")))))

           :trader/modify-order
           (if-let [{:keys [order-details]} (get @orders order-id)]
             (let [modified (cond-> {:type :broker/order-modified
                                     :account/id (:account/id action)
                                     :order-id order-id
                                     :asset (or (:asset action) (:asset order-details))
                                     :message "modify accepted"}
                              (some? (:qty action)) (assoc :qty (:qty action))
                              (some? (:limit action)) (assoc :limit (:limit action)))]
               (log modified)
               (m/? (push-update settings push modified)))
             (do
               (log {:paper/modify-reject (str "modify-rejected, unknown order-id " order-id)})
               (m/? (push-update settings push (assoc action
                                                      :type :broker/modify-rejected
                                                      :message "unknown order")))))

           ; else
           (m/? (push-update settings push {:type :broker/message
                                            :message (str "unsupported message type: " type)
                                            :order-action action})))
         (recur))))))

(defmethod p/create-trade-account :paper
  [ctx {:keys [account/id account/settings]} pull push log]
  (assert (:quote-manager ctx) "paper broker requires :quote-manager in ctx")
  (paper-broker-task ctx settings pull push log))
