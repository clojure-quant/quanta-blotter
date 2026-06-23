(ns quanta.blotter.oms.flow.campaign
  (:require
   [missionary.core :as m]
   [quanta.blotter.oms.flow.working-orders :as wo]
   [quanta.blotter.oms.flow.fill :as fill]
   [quanta.blotter.oms.flow.open-positions :as op]))

(defn campaign-tagged-combined-flow
  "ensures that all messages on combined flow have a campaign and label (if they were used)
   needed so that the robot can track order on a campaign level."
  [channel-flow]
  (let [dict (atom {})]
    ;; todo: this implementation will have a memory leak at some time because order-id dict will grow unbounded,
    ;; because it does not remove closed orders from the dict.
    (m/ap
     (let [msg (m/?> 1 channel-flow)
           order-id (:order-id msg)
           msg-extended (case (:type msg)
                          :trader/new-order
                          (let [campaign-id (:campaign msg)
                                label (:label msg)]
                            (when (or campaign-id label)
                              (println "*** adding campaign/label to dict: " order-id " [" campaign-id " " label "]")
                              (println "*** campain dict: " @dict)
                              (swap! dict assoc order-id [campaign-id label]))
                            msg)

                          ; else default                          
                          (if-let [[campaign-id label] (get @dict order-id)]
                            (assoc msg :campaign campaign-id :label label)
                            (do 
                              (println "*** order-id has no compaign: " order-id  "type: " (:type msg))
                              msg))

                            ;:broker/order-filled                        
                            ;:broker/order-canceled
                           ; :broker/order-rejected
                           ; :broker/order-expired
                           ; :broker/order-confirmed, :broker/cancel-confirmed, :trader/cancel-order, default
                            ;state
                          )]
       msg-extended))))

(defn oms-campaign-tagged-combined-flow [oms]
  (campaign-tagged-combined-flow (get-in oms [:consolidator :combined-flow])))

(defn campaign-flows [combined-tagged-flow campaign]
  (let [filtered-combined-tagged-flow  (m/eduction
                                        (filter #(= (:campaign %) campaign))
                                        combined-tagged-flow)
        order-change-flow (m/stream (wo/order-change-flow filtered-combined-tagged-flow))
        fill-flow (m/stream (fill/fill-flow filtered-combined-tagged-flow))
        position-method :fifo
        position-change-flow (m/stream (op/position-change-flow fill-flow {:method position-method}))
        open-position-dict-flow (m/stream (op/open-position-dict-flow position-change-flow))]
    {:working-order-dict-flow (m/stream (wo/working-order-dict-flow order-change-flow))
     :fill-flow fill-flow
     :open-position-dict-flow open-position-dict-flow}))

