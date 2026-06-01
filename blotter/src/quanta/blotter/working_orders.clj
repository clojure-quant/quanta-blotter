(ns quanta.blotter.working-orders
  (:require
   [missionary.core :as m]
   [taoensso.timbre :refer [info]]))

(defn- initial-state []
  {:history []})

(defn- conj-history [state msg]
  (update state :history conj msg))

(defn- terminal? [state]
  (true? (:terminal? state)))

(defn- apply-fill [state {:keys [qty price]}]
  (let [fill-qty (or (:fill-qty state) 0.0)
        fill-notional (or (:fill-notional state) 0.0)
        q (or qty 0.0)
        p (or price 0.0)
        new-fill-qty (+ fill-qty q)
        new-notional (+ fill-notional (* q p))
        order-qty (:qty state)
        filled? (and order-qty (>= new-fill-qty order-qty))]
    (assoc state
           :fill-qty new-fill-qty
           :fill-notional new-notional
           :terminal? (or (terminal? state) filled?))))

(defn- init-from-new-order [state msg]
  (assoc state
         :order-id (:order-id msg)
         :account (:account/id msg)
         :asset (:asset msg)
         :side (:side msg)
         :qty (:qty msg)
         :fill-qty 0.0
         :fill-notional 0.0
         :terminal? false))

(defn- mark-terminal [state]
  (assoc state :terminal? true))

(defn- ready-to-emit? [state]
  (some? (:qty state)))

(defn to-order-view
  "Projects internal accumulator state to the public order map."
  [{:keys [order-id account asset side qty fill-qty fill-notional history terminal?]}]
  (let [qty-filled (or fill-qty 0.0)
        done? (true? terminal?)]
    {:order/id order-id
     :order/account account
     :order/asset asset
     :order/side side
     :order/status (if done? :done :working)
     :order/qty qty
     :order/qty-filled qty-filled
     :order/qty-working (if done? 0.0 (- qty qty-filled))
     :order/avg-price (when (pos? qty-filled) (/ fill-notional qty-filled))
     :order/history history}))

(defn- process-order-msg [state msg]
  (let [state (conj-history state msg)]
    (case (:type msg)
      :trader/new-order
      (if (:qty state)
        state
        (init-from-new-order state msg))

      :broker/order-filled
      (if (:qty state)
        (apply-fill state msg)
        state)

      :broker/order-canceled
      (if (:qty state)
        (mark-terminal state)
        state)

      :broker/order-rejected
      (if (:qty state)
        (mark-terminal state)
        state)

      :broker/order-expired
      (if (:qty state)
        (mark-terminal state)
        state)

      ;; :broker/order-confirmed, :broker/cancel-confirmed, :trader/cancel-order, default
      state)))

(defn- per-order-view-flow
  "Consumes messages for one order-id; emits {:order/...} after each message
   once :trader/new-order has been seen."
  [order-flow]
  (m/eduction
   (filter ready-to-emit?)
   (map to-order-view)
   (m/reductions process-order-msg (initial-state) order-flow)))

(defn order-change-flow
  "Consumes mixed channel flow and emits {:order/...} maps — one per channel
   message per order-id, after that order's :trader/new-order."
  [channel-flow]
  (m/ap
   (let [[order-id order-flow] (m/?> ##Inf (m/group-by :order-id channel-flow))
         _ (info "working-order flow for order-id:" order-id)
         order-status (m/?> 1 (per-order-view-flow order-flow))]
     order-status)))
