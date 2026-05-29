(ns quanta.market.trade.order-state
  (:require
   [missionary.core :as m]
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [quanta.market.util :refer [flow-sender start-logging mix]]
   [quanta.market.trade.schema :as s]
   [quanta.market.trade.db :refer [store-new-order! store-order-update!]]
   [quanta.market.trade.working-order :as wo]))

(defn get-working-orders [{:keys [working-orders]}]
  (vals @working-orders))

(defn get-working-order [{:keys [working-orders]} order-id]
  (get @working-orders order-id))

(defn create-working-order [{:keys [db working-orders] :as state}
                            {:keys [order-id] :as order}]
  (let [working-order (wo/create-working-order order)]
    (swap! working-orders assoc order-id working-order)
    (when db
      (store-new-order! db working-order))))


(defn process-order-update [{:keys [db working-orders] :as state} working-order order-update]
  (let [{:keys [new-order-status new-trade alert]} (wo/process-order-update working-order order-update)
        order-id (-> working-order :order :order-id)]

    (when alert
      (error "order-update-alert: " alert))
  
    (when new-trade
      (warn "order-update new trade: " new-trade))

    (when new-order-status
      (swap! working-orders assoc-in [order-id :order-status] new-order-status)
      (when db
        (store-order-update! db order-update new-order-status))
      (when-not (wo/open? new-order-status)
        (warn "removing closed order-status: " new-order-status "order: "  (:order working-order))
        (swap! working-orders dissoc order-id)))))


(defn create-update-task [{:keys [db alert :order-orderupdate-flow] :as state}]
  (assert order-orderupdate-flow ":order-update-flow option missing")
  (m/reduce (fn [_r {:keys [order order-update] :as msg}]
              ;(alert "msg-received: " msg)
              (when order
                (alert "processing order: " order)
                (create-working-order state order))
              (when order-update ; ignore nil
                (alert "processing order-update: " order-update)
                (if (s/validate-order-update order-update)
                  (let [working-order (get-working-order state (:order-id order-update))]
                    (if working-order
                      (process-order-update state working-order order-update)
                      (alert "order not found - cannot process order update!" working-order)))
                  (alert "order-update validation failed"
                         {:validation-error (s/human-error-order-update order-update)
                          :order-update order-update})))
              nil)
            nil order-orderupdate-flow))



(defn order-manager-start
  "starts the order manager.
   db is optional. if no db is passed, the database will not get updated and you 
   are working purely in memory."
  [{:keys [db order-orderupdate-flow alert-logfile]}]
  ; load-working-orders from db.
  (let [working-orders (atom {})
        ; alerts
        alert-flow-sender (flow-sender)
        send-alert (:send alert-flow-sender)
        alert-flow (:flow alert-flow-sender)
        alert (fn [text data]
                (warn "alert: text: " text " data: " data)
                (send-alert {:text text
                             :data data}))
        alert-logger-dispose! (if alert-logfile
                                (do (info "order-manager is logging to: " alert-logfile)
                                    (start-logging alert-logfile alert-flow))
                                (warn "order-manager is NOT LOGGING!"))
        state {:db db
               :working-orders working-orders
               :order-orderupdate-flow order-orderupdate-flow
               :alert alert
               :alert-flow alert-flow
               :alert-logger-dispose! alert-logger-dispose!}
        update-task (create-update-task state)
        stop-update-processor (update-task #(info "order-update-processor stopped successfully: " %)
                                           #(error "order-update-processor crashed: " %))]
    (assoc state
           :stop-update-processor stop-update-processor)))


(defn order-manager-stop [{:keys [stop-update-processor]}]
  (info "order-manager stopping..")
  (stop-update-processor))