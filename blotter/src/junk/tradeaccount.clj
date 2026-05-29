(ns quanta.market.broker.bybit.tradeaccount
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [missionary.core :as m]
   [quanta.market.protocol :as p]
   [quanta.market.util :refer [mix]]
   [quanta.market.broker.bybit.websocket :refer [create-websocket]]
   [quanta.market.broker.bybit.task.order :as o]
   [quanta.market.broker.bybit.topic.orderupdate :as ou]
   [quanta.market.broker.bybit.task.subscription :as s]))

(defrecord bybit-trade [opts websocket-order websocket-orderupdate]
  p/tradeaccount
  (start-trade [this]
    (info "connecting bybit-trade websockets ")
    (p/start! websocket-order)
    (p/start! websocket-orderupdate)
     ; ticketInfo does not work.
    (info "subscribing bybit order-updates..")
     ;(m/? (s/subscription-start! (p/current-connection websocket-orderupdate) :order/execution))
    (m/? (s/subscription-start! (p/current-connection websocket-orderupdate) :order/update))
    opts)
  (stop-trade [this]
    (info "closing bybit-trade websockets ")
    (p/stop! websocket-order)
    (p/stop! websocket-orderupdate)
    opts)
  (msg-flow [this]
    (let [order-in (p/msg-in-flow websocket-order)
          order-out (p/msg-out-flow websocket-order)
          orderupdate-in (p/msg-in-flow websocket-orderupdate)
          orderupdate-out (p/msg-out-flow websocket-orderupdate)]
      (assert order-in "order-in-msg flow nil")
      (assert order-out "order-in-msg flow nil")
      (assert orderupdate-in "order-in-msg flow nil")
      (assert orderupdate-out "order-in-msg flow nil")
      (mix order-in order-out orderupdate-in orderupdate-out)))
  (order-update-msg-flow [this]
    (let [flow (p/msg-in-flow websocket-orderupdate)]
      (assert flow "msg-in-flow for websocket-orderupdate nil.")
      (ou/order-update-msg-flow flow)))
  (order-update-flow [this]
    (let [flow (p/order-update-msg-flow this)]
      (assert flow "order-update-msg-flow for websocket-orderupdate nil.")
      (ou/order-update-flow flow)))
  p/trade-action
  (order-create! [this order]
    (let [cur-conn (p/current-connection websocket-order)]
      (assert cur-conn "cannot create order - cur-conn nil")
      (o/order-create! cur-conn order)))
  (order-cancel! [this order]
    (o/order-cancel! (p/current-connection websocket-order) order))
; 
  )
(defmethod p/create-tradeaccount :bybit
  [{:keys [creds mode] :as opts}]
  (assert creds "bybit tradefeed needs :creds")
  (assert mode "bybit tradefeed needs :mode (:test :main)")
  (info "creating bybit tradefeed: " opts)
  (let [websocket-order
        (create-websocket
         (merge opts {:segment :trade}))
        websocket-orderupdate
        (create-websocket
         (merge opts {:segment :private}))]
    (bybit-trade. opts websocket-order websocket-orderupdate)))


