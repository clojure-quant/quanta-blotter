(ns quanta.blotter.oms.portfolio
  "Synchronous OMS portfolio: one message in → updated dict state + sparse out-msg."
  (:require
   [missionary.core :as m]
   [taoensso.timbre :refer [info error]]
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.portfolio.trader :as trader]
   [quanta.blotter.oms.portfolio.working-order :as wo]
   [quanta.blotter.oms.portfolio.open-position :as op]))

(defn empty-state
  "Empty portfolio reducer state."
  ([]
   (empty-state {:position-method :fifo}))
  ([{:keys [position-method] :or {position-method :fifo}}]
   {:working-order {}
    :open-position {}
    :position-method position-method}))

(defn- public-state
  "Public view of portfolio state (dicts only)."
  [{:keys [working-order open-position]}]
  {:working-order working-order
   :open-position open-position})

(defn process-message
  "Pure synchronous portfolio step.
   Returns {:state new-state :out-msg sparse-out-msg}.
   Every out-msg includes :msg (the received channel message)."
  [state msg]
  (let [method (:position-method state)

        out {:msg msg}

        ;; trader request
        out (cond-> out
              (trader/trader? msg) (assoc :trader msg))

        ;; working order (order/orderupdate)
        {:keys [working-order trade] :as out-wo}
        (wo/process-order-orderupdate-message (:working-order state) msg)
        out (merge out out-wo)
        state (if working-order 
                (assoc state :working-order working-order)
                state)

        ;; open position (from trade)
        {:keys [open-position] :as out-op}
        (op/process-trade (:open-position state) trade {:method method})
        out (merge out out-op)
        state (if open-position
                (assoc state :open-position open-position)
                state)]
    {:state state
     :out-msg out}))

(defn- strip-order-db-fields [order]
  (-> order
      (dissoc :db/id :order/account-db)
      (assoc :order/history (if (sequential? (:order/history order))
                              (:order/history order)
                              []))))

(defn- strip-position-db-fields [position]
  (dissoc position :db/id :position/account-db))

(defn- hydrate-from-db
  "Rebuild portfolio state from persisted open orders and open positions.
   Only open positions are loaded into `:open-position`."
  [db opts]
  (let [state (empty-state opts)
        open-orders (when db (or (db/query-open-orders db) []))
        open-positions (when db (or (db/query-open-positions db) []))
        state (reduce
               (fn [st order]
                 (let [view (strip-order-db-fields order)
                       oid (:order/id view)
                       hydrated (wo/hydrate-order view)]
                   (assoc-in st [:working-order oid] hydrated)))
               state
               open-orders)]
    (reduce
     (fn [st position]
       (let [view (strip-position-db-fields position)
             k (op/position-key view)
             hydrated (op/hydrate-position view)]
         (assoc-in st [:open-position k] hydrated)))
     state
     open-positions)))

(defn portfolio-create
  "Build portfolio folding over `channel-flow` (does not start consuming).
   Returns {:state atom-of-public-dicts
            :out-flow shared missionary stream of sparse out-msg maps
            :dispose! fn
            :position-method keyword}.
   Call `portfolio-start!` after attaching `:out-flow` consumers."
  ([db channel-flow]
   (portfolio-create db channel-flow {:position-method :fifo}))
  ([db channel-flow {:keys [position-method] :or {position-method :fifo}}]
   (assert channel-flow "portfolio-create needs channel-flow")
   (let [opts {:position-method position-method}
         initial (if db
                   (hydrate-from-db db opts)
                   (empty-state opts))
         state-a (atom (public-state initial))
         reducer-a (atom initial)
         out-flow (m/stream
                   (m/eduction
                    (map (fn [msg]
                           (let [{:keys [state out-msg]} (process-message @reducer-a msg)]
                             (reset! reducer-a state)
                             (reset! state-a (public-state state))
                             out-msg)))
                    channel-flow))
         dispose-a (atom nil)
         dispose! (fn []
                    (when-let [d @dispose-a]
                      (d)
                      (reset! dispose-a nil)))]
     {:state state-a
      :out-flow out-flow
      :dispose-a dispose-a
      :dispose! dispose!
      :position-method position-method})))

(defn portfolio-start!
  "Start retaining drain on portfolio `:out-flow` (idempotent).
   Attach out-flow consumers before calling this when they must see all events."
  [{:keys [out-flow dispose-a] :as portfolio}]
  (assert out-flow "portfolio-start! needs :out-flow")
  (assert dispose-a "portfolio-start! needs :dispose-a")
  (when-not @dispose-a
    (reset! dispose-a
            ((m/reduce (fn [_ _] nil) nil out-flow)
             #(info "portfolio out-flow done" %)
             #(error "portfolio out-flow error" %))))
  portfolio)

(defn portfolio-stop!
  [{:keys [dispose!]}]
  (when dispose! (dispose!)))

(defn out-key-flow
  "Derive a flow of values for a single out-msg key from portfolio :out-flow."
  [out-flow k]
  (m/eduction
   (keep (fn [out-msg] (get out-msg k)))
   out-flow))
