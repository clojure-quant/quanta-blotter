(ns quanta.blotter.consolidator
  (:require
   [missionary.core :as m]
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [quanta.blotter.util-rdv :refer [create-rdv]]))

(defn msg-flow [!-a]
  ; without the stream the last subscriber gets all messages
  (m/stream
   (m/observe
    (fn [!]
      (reset! !-a !)
      (fn []
        (reset! !-a nil))))))

(defn flow-sender
  "returns {:flow f
            :send s}
    (s v) pushes v to f."
  []
  (let [!-a (atom nil)]
    {:flow (msg-flow !-a)
     :send (fn [v]
             (if-let [! @!-a]
               (! v)
               (warn "consolidator: flow-sender: no flow to send to" v))
               )}))

(defn create-consolidator [{:keys [order orderupdate log] :as channel}]
  (assert order "consolidator needs order")
  (assert orderupdate "consolidator needs orderupdate")
  (assert log "consolidator needs log")
  (let [{:keys [flow send]} (flow-sender)
        order-2-rdv (create-rdv "consolidator/order-out")
        orderupdate-2-rdv (create-rdv "consolidator/orderupdate-in")]
    {:send send
     :combined-flow flow
     :channel-original channel
     :channel {:order order-2-rdv
               :orderupdate orderupdate-2-rdv
               :log log}
     :dispose! (atom nil)}))

(defn start-consolidator! [{:keys [send _combined-flow channel-original channel dispose!] :as this}]
  (let [{:keys [order orderupdate _log]} channel-original
        order-2-rdv (:order channel)
        orderupdate-2-rdv (:orderupdate channel)
        copy-order-sp (m/sp
                       (loop []
                         (let [data (m/? order)] ; read original order
                           ;(println "ORDER: " data)
                           (send data)
                           (m/? (order-2-rdv data)) ; copy order 
                           (recur))))
        copy-orderupdate-sp (m/sp
                             (loop []
                               (let [data (m/? orderupdate-2-rdv)] ; read original orderupdate
                                 ;(println "ORDERUPDATE: " data)
                                 (send data)
                                 (m/? (orderupdate data)) ; copy orderupdate
                                 (recur))))
        t (m/join concat copy-order-sp copy-orderupdate-sp)
        dispose (t #(println "consolidator done" %) #(println "consolidator error" %))]
    dispose))

(defn stop-consolidator! [{:keys [dispose!] :as this}]
  (when-let [d @dispose!]
    (d)))

