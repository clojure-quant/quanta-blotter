(ns quanta.market.trade.order
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [tick.core :as t]
   [missionary.core :as m]))

(defn- filled? [{:keys [order-open order-status] :as working-order}]
  (>= (:fill-qty order-status) (:qty order-open)))

(defn- extract-trade [{:keys [open-order order-status] :as working-order}
                      {:keys [fill-qty fill-value] :as broker-order-status}]
  (if (and fill-qty fill-value)
    (let [_ (info "extracting trade: " broker-order-status)
          cur-fill-qty (:fill-qty order-status)
          cur-fill-value (:fill-value order-status)
          open-qty (- (:qty open-order) cur-fill-qty)
          broker-trade-qty (- fill-qty cur-fill-qty)
          broker-trade-value (- fill-value cur-fill-value)
          _ (info "broker-qty-trade: " broker-trade-qty)]
      (if (> broker-trade-qty 0)
        (if (<= broker-trade-qty open-qty)
          {:new-trade {:qty broker-trade-qty :value broker-trade-value}}
          {:new-trade {:qty open-qty :value (/ (* broker-trade-value open-qty) broker-trade-qty)}
           :alert-trade {:text "received order-status that has fill BIGGER than initial order qty."
                         :data broker-order-status}})
        {}))
    {}))

(defn- process-order-status
  "reducer function 
   input : broker-order-status updates
   state is a working order (:open-order :order-status)
   returns :transactions which are not kept in state"
  [{:keys [open-order order-status] :as working-order}
   {:keys [status close-reason] :as broker-order-status}]
  (let [; status prior to applying the broker-order-status
        cur-fill-qty (:fill-qty order-status)
        cur-fill-value (:fill-value order-status)
        ; extract new-trade 
        {:keys [new-trade alert-trade]} (extract-trade working-order broker-order-status)
        ; new values
        new-fill-status (if new-trade
                          {:fill-qty (+ (:qty new-trade) cur-fill-qty)
                           :fill-value (+ (:value new-trade) cur-fill-value)}
                          {})
        filled? (when (:fill-qty new-fill-status)
                  (>= (:fill-qty new-fill-status) (:qty open-order)))
        new-close-status  (cond
                            filled? {:status :closed
                                     :close-reason "filled"
                                     :close-date (t/inst)}
                            (= status :closed) {:status :closed
                                                :close-reason close-reason
                                                :close-date (t/inst)}
                            :else {})
        close-trans (if (= :closed (:status new-close-status))
                      {:order/close new-close-status}
                      {})
        trade-transaction (if new-trade {:trade new-trade} {})]
    {:open-order open-order
     :order-status (merge order-status
                          new-fill-status
                          new-close-status)
     :transactions (merge trade-transaction close-trans)}))

(defn- create-working-order [order]
  {:open-order order
   :order-status {:status :open
                  :open-date (t/inst)
                  :fill-qty 0.0
                  :fill-value 0.0}
   :transactions {:order/created order}})

(defn- working-order-flow
  "returns a flow 
   that consumes order-orderupdate-flow (so the orderupdate-flow filtered 
   to the order-id)
   returns {:open-order :order-status :transaction} map.
   working-order (:open-order :order-status) is stateful.
   :transactions are not stateful, so it means they are only sent once.
   this flow can be used to update the order-status of a working-order ui,
   and it can be used to extract events like :alert :order/open :order/close"
  [order-orderupdate-flow]
  (m/reductions (fn [{:keys [open-order order-status] :as working-order}
                     {:keys [order broker-order-status] :as msg}]
                  (cond
                    ; new order
                    (and (nil? open-order) order)
                    (create-working-order order)
                    ; order update  
                    (and open-order broker-order-status)
                    (let [new-working-order (process-order-status working-order broker-order-status)
                          order-closed? (get-in new-working-order [:transactions :order/close])]
                      (if order-closed?
                        (reduced new-working-order)
                        new-working-order))
                    ; order update no order
                    (and (nil? open-order) broker-order-status)
                    (assoc working-order
                           :transactions {:alert {:text "ignoring order-update to unknown order!"
                                                  :data {:working-order working-order
                                                         :msg msg}}})
                    :else
                    (assoc working-order
                           :transactions {:alert {:text "order-update is ignored - other reason"
                                                  :data {:working-order working-order
                                                         :msg msg}}})))
                {}
                order-orderupdate-flow))

(defn order-change-flow
  "returns a flow that 
   reads the order-orderupdate-flow and 
   outputs messages {:order-id :order-status :transaction}
   whereas :order-status is stateful and
   :transaction is transactional"
  [order-orderupdate-flow]
  (m/ap
   (let [[order-id >x] (m/?> ##Inf (m/group-by :order-id order-orderupdate-flow))
         _ (info "creating flow for order-id: " order-id)
         working-order (m/?> 1 (working-order-flow >x))]
     [order-id working-order])))

(defn working-orders-flow
  "returns a flow, 
   sending dictionary of all working orders."
  [order-change-flow]
  (m/ap
   (let [orders (atom {})
         [order-id {:keys [order-status open-order transactions]}] (m/?> 1 order-change-flow)
         order-open (:order/open transactions)
         order-close (:order/close transactions)]
     (cond
       order-open (m/amb)
       order-close (swap! orders dissoc order-id)
       :else
       (swap! orders assoc order-id {:order-status order-status
                                     :open-order open-order})))))

(defn order-dict->order-seq-flow [working-order-dict-flow]
  (m/eduction (map (fn [order-dict]
                     (map (fn [[order-id working-order]]
                                ;  working-order
                            (assoc working-order :order-id order-id)) order-dict)))
              working-order-dict-flow))

(defn order-status->working-order-flow [order-status-flow]
  ; TODO: make this work with a m/sp
  #_(let [last-status (m/? (m/reduce (fn [_r v] v) {} order-status-flow))]
      (map (fn [[order-id working-order]]
           ;  working-order
             (assoc working-order :order-id order-id)) last-status)))

(defn current-working-orders
  "snapshot of current workign orders.
   used in tests - DO NOT USE IN REALTIME!"
  [order-orderupdate-flow]
  ; TODO: make this working with a m/sp
  #_(let [order-changes (order-change-flow order-orderupdate-flow)
          order-status (working-orders-flow order-changes)
          last-status (m/? (m/reduce (fn [_r v] v) {} order-status))]
      (map (fn [[order-id working-order]]
           ;  working-order
             (assoc working-order :order-id order-id)) last-status)))

(defn- new-trade?
  "predicate if a order-update msg has a new trade"
  [order-update-msg]
  (info "new-trade order-update-msg: " order-update-msg)
  (let [[order-id {:keys [transactions]}] order-update-msg
        new-trade (:trade transactions)]
    new-trade))

(defn- order-update-msg->trade [order-update-msg]
  (let [[order-id {:keys [transactions open-order]}] order-update-msg
        new-trade (:trade transactions)]
    (assoc new-trade
           :order-id order-id
           :account (:account open-order)
           :asset (:asset open-order)
           :side (:side open-order))))

(defn trade-flow
  "creates a flow that has new-trades.
   useful to trigger opening of positions"
  [order-change-flow]
  (m/eduction (filter new-trade?)
              (map order-update-msg->trade)
              order-change-flow))
