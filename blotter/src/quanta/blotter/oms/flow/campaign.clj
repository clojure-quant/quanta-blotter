(ns quanta.blotter.oms.flow.campaign
  (:require
   [missionary.core :as m]
   [taoensso.timbre :refer [info]]
   [quanta.blotter.oms.portfolio :as portfolio]))

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
                              (swap! dict assoc order-id [campaign-id label]))
                            msg)

                          (if-let [[campaign-id label] (get @dict order-id)]
                            (assoc msg :campaign campaign-id :label label)
                            (do
                              (info "order-id has no compaign: " order-id  "type: " (:type msg))
                              msg)))]
       msg-extended))))

(defn campaign-portfolio
  "Filtered portfolio for a single campaign-id (not started).
   Returns the map from `portfolio-create`; caller should `portfolio-start!`."
  ([combined-tagged-flow campaign]
   (campaign-portfolio combined-tagged-flow campaign {:position-method :fifo}))
  ([combined-tagged-flow campaign opts]
   (let [filtered (m/stream
                   (m/eduction
                    (filter #(= (:campaign %) campaign))
                    combined-tagged-flow))]
     (portfolio/portfolio-create nil filtered opts))))

;; backwards-compatible alias used by stresstest / tests during migration naming
(defn campaign-flows
  "Start a campaign-scoped portfolio. Prefer `campaign-portfolio`.
   Returns portfolio map with :state and :out-flow."
  [combined-tagged-flow campaign]
  (campaign-portfolio combined-tagged-flow campaign))
