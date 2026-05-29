(ns quanta.market.portfolio
  (:require
   [missionary.core :as m]
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [nano-id.core :refer [nano-id]]
   [tick.core :as t]
   [quanta.market.util :refer [flow-sender start-logging mix current-v]]
   [quanta.market.protocol :as p]
   [quanta.market.trade.schema :as s]
   [quanta.market.trade.transactor :refer [transactor-start transactor-log-start!
                                           transactor-stop transactor-log-stop!]]))

(defn only-valid-order-update [f]
  (m/eduction
   (filter s/validate-broker-order-status) f))

(defn only-bad-order-update [f]
  (m/eduction
   (remove s/validate-broker-order-status)
   (map (fn [order-update]
          {:broker-order-status order-update
           :validation-error (s/human-error-broker-order-status order-update)}))
   f))

(defn create-flows [tm]
  (let [order-create-flow-sender (flow-sender)
        order-flow (:flow order-create-flow-sender)
        send-new-order-to-flow (fn [order-update]
                                 (warn "new order: " order-update)
                                 ((:send order-create-flow-sender) order-update))
        orderupdate-flow (p/orderupdate-flow tm)
        ok-order-update-flow (only-valid-order-update orderupdate-flow)
        bad-order-update-flow (only-bad-order-update orderupdate-flow)

        order-orderupdate-flow (mix ok-order-update-flow order-flow)]
    {:order-orderupdate-flow order-orderupdate-flow
     :send-new-order-to-flow send-new-order-to-flow
     :bad-order-update-flow bad-order-update-flow}))

(defn get-reject-reason [order-response]
  ; {:msg/type :order/rejected,
  ;  :message "Order value exceeded lower limit.",
  ;  :code 170140}
  ; {:msg/type :order/confirmed}
  (let [msg-type (:msg/type order-response)
        reject-reason (:message order-response)]
    (when (= :order/rejected msg-type)
      (warn "order was rejected. reason: " reject-reason)
      reject-reason)))

(defn- psnapshot [working-orders open-positions]
  {:working-orders working-orders
   :open-positions open-positions})

(defrecord portfolio-manager [db tm transactor send-new-order-to-flow transactor-log]
  p/trade-action
  (order-create! [this {:keys [account asset side quantity type] :as order}]
    (m/sp
     (if (s/validate-order order)
       (let [order-id (nano-id 8)
             order (assoc order
                          :order-id order-id
                          :date-created (t/inst))]
         (if tm
           (do (send-new-order-to-flow {:order-id order-id
                                        :order order})
               (let [response (m/? (p/order-create! tm order))
                     reject-reason (get-reject-reason response)]
                 (when reject-reason
                   (send-new-order-to-flow {:order-id order-id
                                            :broker-order-status {:status :closed
                                                                  :reject-reason reject-reason}}))
                 response))
           (error "cannot send order - :tm nil")))
       (do
         (error "order invalid error: " (s/human-error-order order))
         (throw (ex-info "order-invalid" {:order order
                                          :error (s/human-error-order order)}))))))
  (order-cancel! [this {:keys [account] :as order-cancel}]
    (if tm
      (p/order-cancel! tm order-cancel)
      (error "cannot cancel order - :tm nil")))

  p/portfolio
  (working-order-f [{:keys [transactor]}]
    (:working-order-f transactor))
  (open-position-f [{:keys [transactor]}]
    (:open-position-f transactor))
  (trade-f [{:keys [transactor]}]
    (:trade-f transactor))
  (snapshot [{:keys [transactor]}]
    @(:snapshot-a transactor))
  ;
  )

(defn portfolio-manager-start
  "portfolio-manager uses both broker-account-manager and the transactor.
   it therefore is able to create/cancel orders, and know working-orders and open positions.
   db is optional. if no db is passed, the database will not get updated and you 
   are working purely in memory."
  [{:keys [db tm transactor-logfile alert-logfile]}]
  (let [{:keys [order-orderupdate-flow
                send-new-order-to-flow
                bad-order-update-flow]} (create-flows tm)
        transactor (transactor-start {:order-orderupdate-flow order-orderupdate-flow
                                      :db db})
        transactor-log (transactor-log-start! transactor transactor-logfile)
        transactor-alert-f (:alert-f transactor)
        alert-f (mix bad-order-update-flow transactor-alert-f)]
    (when alert-logfile
      (start-logging alert-logfile alert-f))
    (portfolio-manager. db tm transactor send-new-order-to-flow transactor-log)))

(defn portfolio-manager-stop [{:keys [transactor transactor-log]}]
  (info "portfolio-manager stopping..")
  (when transactor
    (transactor-stop transactor))
  (when transactor-log
    (transactor-log-stop! transactor-log))
  ;
  )
(defn get-working-orders [{:keys [transactor]}]
  ; TODO: make this working with a sp
  (let [working-order-f (:working-order-f transactor)
        cv (current-v working-order-f)]
    ;(m/? cv)
    cv))




