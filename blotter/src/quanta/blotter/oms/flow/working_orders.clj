(ns quanta.blotter.oms.flow.working-orders
  (:require
   [missionary.core :as m]
   [quanta.blotter.precision :as precision]
   [tick.core :as t]
   [taoensso.timbre :refer [info]]))

(def closed-statuses
  "Order statuses that mean the order is no longer open."
  #{:cancelled :rejected :expired :filled})

(defn order-done? [order]
  (contains? closed-statuses (:order/status order)))

(defn- initial-state []
  {:history []})

(defn- conj-history [state msg]
  (update state :history conj msg))

(defn- terminal? [state]
  (true? (:terminal? state)))

(defn- stamp-order-date [state msg]
  (if (and (nil? (:order-date state)) (:date msg))
    (assoc state :order-date (t/inst (:date msg)))
    state))

(defn- apply-fill [state {:keys [qty price]}]
  (let [fill-qty (or (:fill-qty state) 0M)
        fill-notional (or (:fill-notional state) 0M)
        q (if qty (bigdec qty) 0M)
        p (if price (bigdec price) 0M)
        new-fill-qty (+ fill-qty q)
        new-notional (+ fill-notional (* q p))
        order-qty (:qty state)
        filled? (and order-qty (>= new-fill-qty order-qty))
        price-scale (max (or (:price-scale state) 0)
                         (.scale ^BigDecimal p))]
    (cond-> (assoc state
                   :fill-qty new-fill-qty
                   :fill-notional new-notional
                   :price-scale price-scale)
      filled? (assoc :terminal? true :terminal-status :filled))))

(defn- apply-modify [state {:keys [qty limit]}]
  (cond-> state
    qty (assoc :qty qty)
    limit (assoc :limit limit)))


(defn- init-from-new-order [state msg]
  (assoc state
         :order-id (:order-id msg)
         :account (:account/id msg)
         :asset (:asset msg)
         :side (:side msg)
         :qty (some-> (:qty msg) bigdec)
         :order-type (:order-type msg)
         :fill-qty 0M
         :fill-notional 0M
         :price-scale 0
         :terminal? false
         :terminal-status nil
         :reject-text nil
         :campaign (:campaign msg)
         :label (:label msg)))

(defn- mark-terminal [state status & {:keys [text]}]
  (cond-> (assoc state :terminal? true :terminal-status status)
    text (assoc :reject-text text)))

(defn- ready-to-emit? [state]
  (some? (:qty state)))

(defn to-order-view
  "Projects internal accumulator state to the public order map."
  [{:keys [order-id account asset side qty order-type fill-qty fill-notional
           price-scale history terminal? terminal-status reject-text order-date
           campaign label]}]
  (let [qty-filled (or fill-qty 0M)
        scale (or price-scale 0)
        done? (true? terminal?)]
    (cond-> {:order/id order-id
             :order/account-id account
             :order/asset asset
             :order/side side
             :order/type order-type
             :order/status (if done? terminal-status :working)
             :order/qty qty
             :order/qty-filled qty-filled
             :order/qty-working (if done? 0M (- qty qty-filled))
             :order/avg-price (when (pos? qty-filled) (precision/div fill-notional qty-filled scale))
             :order/date (t/inst (or order-date (t/instant)))
             :order/history history}
      (and done? (= :rejected terminal-status) reject-text)
      (assoc :order/text (str reject-text))
      campaign (assoc :order/campaign campaign)
      label (assoc :order/label label))))

(defn- process-order-msg [state msg]
  (let [state (-> state (conj-history msg) (stamp-order-date msg))]
    (case (:type msg)
      :trader/new-order
      (if (:qty state)
        state
        (init-from-new-order state msg))

      :broker/order-modified
      (apply-modify state msg)

      :broker/order-filled
      (if (:qty state)
        (apply-fill state msg)
        state)

      :broker/order-canceled
      (if (:qty state)
        (mark-terminal state :cancelled)
        state)

      :broker/order-rejected
      (if (:qty state)
        (mark-terminal state :rejected :text (:message msg))
        state)

      :broker/order-expired
      (if (:qty state)
        (mark-terminal state :expired)
        state)

      ;; no effect on order-state:
      
      :broker/order-confirmed
      state 

      :broker/cancel-confirmed
      state

      :trader/cancel-order 
      state 

      :broker/cancel-rejected
      state

      :trader/modify-order
      state
      
      :broker/modify-rejected
      state

      ; default
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

(defn working-order-dict-flow
  "Latest {:order/...} per order-id; removes closed orders."
  [order-change-f]
  (m/reductions
   (fn [acc order]
     (let [k (:order/id order)]
       (if (contains? closed-statuses (:order/status order))
         (dissoc acc k)
         (assoc acc k order))))
   {}
   order-change-f))

(defn working-order-list-flow
  "Emits a vector of working (open) orders, sorted by order-id."
  [order-change-f]
  (m/eduction
   (map (fn [dict] (vals dict)))
   (working-order-dict-flow order-change-f)))

(defn working-order-list-from-dict-flow
  "Emits a sorted vector of working orders from a shared dict flow."
  [dict-flow]
  (m/eduction
   (map (fn [dict] (sort-by :order/id (vals dict))))
   dict-flow))

(defn closed-order-list-flow [order-change-f]
  (m/eduction
   (filter order-done?)
   order-change-f))