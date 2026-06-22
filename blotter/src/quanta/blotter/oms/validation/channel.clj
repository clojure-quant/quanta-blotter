(ns quanta.blotter.oms.validation.channel
  (:require
   [missionary.core :as m]
   [quanta.blotter.util-rdv :refer [create-rdv]]
   [quanta.blotter.oms.validation.schema :as s]))

(defn- spec-error-text [message]
  (str "spec-error " (pr-str (s/human-error-message message))))

(defn- order-rejection [order]
  (cond-> {:type :broker/order-rejected
           :message (spec-error-text order)}
    (:account/id order) (assoc :account/id (:account/id order))
    (:order-id order) (assoc :order-id (:order-id order))))

(defn create-validation-channel
  "Channel middleware that validates orders and orderupdates against the OMS schema.

  `channel` is the input side (toward brokers). The returned `:channel` is the
  output side (toward callers).

  Optional `output-channel` reuses existing rdvs on the output side (e.g.
  consolidator inner channels)."
  ([channel]
   (create-validation-channel channel nil))
  ([{:keys [order orderupdate log] :as channel} output-channel]
   (assert order "validation channel needs order")
   (assert orderupdate "validation channel needs orderupdate")
   (assert log "validation channel needs log")
   (let [order-out-rdv (or (:order output-channel) (create-rdv "validation/order-out"))
         orderupdate-out-rdv (or (:orderupdate output-channel) (create-rdv "validation/orderupdate-out"))]
     {:channel-original channel
      :channel {:order order-out-rdv
                :orderupdate orderupdate-out-rdv
                :log log}
      :dispose! (atom nil)})))

(defn start-validation-channel!
  [{:keys [channel-original channel dispose!]}]
  (let [{:keys [order orderupdate log]} channel-original
        order-out-rdv (:order channel)
        orderupdate-out-rdv (:orderupdate channel)
        validate-order-sp (m/sp
                           (loop []
                             (let [data (m/? order-out-rdv)]
                               (if (s/validate-message data)
                                 (m/? (order data))
                                 (m/? (orderupdate-out-rdv (order-rejection data))))
                               (recur))))
        validate-orderupdate-sp (m/sp
                                 (loop []
                                   (let [data (m/? orderupdate)]
                                     (if (s/validate-message data)
                                       (m/? (orderupdate-out-rdv data))
                                       (log {:schema/error (pr-str (s/human-error-message data))
                                             :original-msg data}))
                                     (recur))))
        t (m/join concat validate-order-sp validate-orderupdate-sp)
        dispose (t #(println "validation channel done" %)
                   #(println "validation channel error" %))]
    (reset! dispose! dispose)
    dispose))

(defn stop-validation-channel!
  [{:keys [dispose!]}]
  (when-let [d @dispose!]
    (d)
    (reset! dispose! nil)))
