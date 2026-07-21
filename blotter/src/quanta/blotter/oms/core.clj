(ns quanta.blotter.oms.core
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [missionary.core :as m]
   [nano-id.core :refer [nano-id]]
   [tick.core :as t]
   [quanta.missionary.logger :refer [create-logger log start-log-flow-to-logger]]
   [quanta.blotter.consolidator :refer [create-consolidator start-consolidator!]]
   [quanta.blotter.oms.flow.campaign :refer [campaign-tagged-combined-flow]]
   [quanta.blotter.oms.validation.channel :refer [create-validation-channel start-validation-channel! stop-validation-channel!]]
   [quanta.blotter.oms.validation.schema :refer [validate-trader-message human-error-trader-message]]
   [quanta.blotter.account-manager :refer [create-account-manager start-account-manager add-edn-account add-edn-accounts]]
   [quanta.blotter.util-rdv :refer [create-rdv]]
   [quanta.blotter.oms.flow.trading-state :as trading-state]))


(defn- create-validated-channel-stack
  "Consolidator (public) -> validator -> account manager."
  [ctx order-rdv orderupdate-rdv account-log-dir]
  (let [consolidator (create-consolidator {:order order-rdv
                                           :orderupdate orderupdate-rdv})
        {:keys [order orderupdate]} (:channel consolidator)
        account-order-rdv (create-rdv "oms/account/order")
        account-orderupdate-rdv (create-rdv "oms/account/orderupdate")
        validator (create-validation-channel {:order account-order-rdv
                                              :orderupdate account-orderupdate-rdv}
                                             {:order order
                                              :orderupdate orderupdate})
        account-manager (create-account-manager ctx
                                                account-order-rdv
                                                account-orderupdate-rdv
                                                {:account-log-dir account-log-dir})]
    {:order-rdv order-rdv
     :orderupdate-rdv orderupdate-rdv
     :consolidator consolidator
     :validator validator
     :account-manager account-manager}))

(defn create-order-manager [{:keys [ctx
                                    ; optional parameters
                                    transaction-log-file account-log-dir validate? tag?]
                             :or {validate? true
                                  tag? true
                                  transaction-log-file nil
                                  account-log-dir nil
                                  }}]
  (let [order-rdv (create-rdv "oms/order")
        orderupdate-rdv (create-rdv "oms/orderupdate")
        log-transaction (when transaction-log-file
                          (create-logger transaction-log-file false))
        {:keys [order-rdv orderupdate-rdv consolidator validator account-manager]}
        (if validate?
          (create-validated-channel-stack ctx order-rdv orderupdate-rdv account-log-dir)
          (let [consolidator (create-consolidator {:order order-rdv
                                                   :orderupdate orderupdate-rdv})
                {:keys [order orderupdate]} (:channel consolidator)]
            {:order-rdv order-rdv
             :orderupdate-rdv orderupdate-rdv
             :consolidator consolidator
             :validator nil
             :account-manager (create-account-manager ctx order orderupdate
                                                      {:account-log-dir account-log-dir})}))
        {:keys [combined-flow]} consolidator
        combined-flow (if tag?
                        (m/stream (campaign-tagged-combined-flow combined-flow))
                        combined-flow)]
    {:config {:validate? validate?
              :tag? tag?}
     :internal {:log-transaction log-transaction
                :dispose-a (atom nil)
                :validator validator
                :consolidator consolidator
                :order-rdv order-rdv
                :orderupdate-rdv orderupdate-rdv}
     :ctx ctx ; quote lookup
     :account-manager account-manager
     :combined-flow combined-flow
     :trading-state (trading-state/create-trading-state! combined-flow)
     ;; todo: move this to oms. 1. oms has db, so we can init from db state 2. om (order multiplexer) does no care about this functionality.
     }))

(defn consume-orderupdate [r]
  (m/sp
   (loop []
     (m/? r)
     (recur))))

(defn start-order-manager!
  "Start paper trade-account 1 fed by simulated orderflow for that account."
  [{:keys [account-manager combined-flow _trading-state internal] :as this}]
  (info "start-order-manager!")
  (let [{:keys [log-transaction dispose-a validator consolidator orderupdate-rdv]} internal
        dispose-transaction-logger (when log-transaction
                                     (start-log-flow-to-logger log-transaction combined-flow))
        dispose-orderupdate-consumer!  ((consume-orderupdate orderupdate-rdv)
                                        #(info "orderupdate-consumer done " %)
                                        #(error "orderupdate-consumer error " %))
        dispose-validation! (when validator
                              (start-validation-channel! validator))
        dispose-consolidator! (start-consolidator! consolidator)
        dispose-account-manager! (start-account-manager account-manager)]
    (reset! dispose-a
            {:dispose-transaction-logger dispose-transaction-logger
             :dispose-orderupdate-consumer! dispose-orderupdate-consumer!
             :dispose-validation! dispose-validation!
             :dispose-consolidator! dispose-consolidator!
             :dispose-account-manager! dispose-account-manager!})
    (when log-transaction
      (log log-transaction {:type :oms/started :date (t/instant)}))
    this))

(defn stop-order-manager! [{:keys [internal] :as this}]
  (info "stop-order-manager!")
  (let [{:keys [dispose-a validator]} internal]
    (when-let [d @dispose-a]
      (:dispose-account-manager! d)
      (when validator
        (stop-validation-channel! validator))
      (:dispose-consolidator! d)
      (:dispose-orderupdate-consumer! d)
      (when-let [dispose-tx (:dispose-transaction-logger d)]
        (dispose-tx))

      (reset! dispose-a nil))
    (dissoc this :trading-state)))

(defn send-message
  "push a message on the OMS order channel. "
  [this message]
  (m/sp
   (assert (map? this) "this (oms) needs to be a map")
   (let [order-rdv (get-in this [:internal :order-rdv])]
     (assert order-rdv "this (oms) needs to have an order-rdv")
     (if (validate-trader-message message)
       (let [message (assoc message :date (t/instant))]
         (m/? (m/via m/blk (info "[OMS send-trader-message]: " message)))
         (m/? (order-rdv message))
         (m/? (m/via m/blk (info "[OMS send-trader-message] success: " message)))
         message)
       (throw (ex-info "Invalid trader message"
                       {:message message
                        :errors (human-error-trader-message message)}))))))

(defn create-order
  "Create an order and push it on the OMS order channel.
   `:order-type` is `:limit` or `:market`; limit orders require `:limit`."
  [this order-details]
  (assert (map? order-details) "order-details must be a map")
  (let [order (-> order-details
                  (assoc :type :trader/new-order)
                  (cond-> (not (:order-id order-details)) (assoc :order-id (nano-id 6))))]
    (send-message this order)))

(defn cancel-order
  "Create an order and push it on the OMS order channel.
   `:order-type` is `:limit` or `:market`; limit orders require `:limit`."
  [this order-details]
  (assert (map? order-details) "order-details must be a map")
  (let [order (-> order-details
                  (assoc :type :trader/cancel-order))]
    (send-message this order)))

(defn modify-order
  "Create an order and push it on the OMS order channel.
   `:order-type` is `:limit` or `:market`; limit orders require `:limit`."
  [this order-details]
  (assert (map? order-details) "order-details must be a map")
  (let [order (-> order-details
                  (assoc :type :trader/modify-order))]
    (send-message this order)))

;; flow sender

(defn send-flow-messages
  "Sends messages from a missionary flow to broker(s).
   useful for testing."
  [this flow]
  (let [process-flow (m/ap (let [v (m/?> flow)]
                             (m/? (send-message this v))))
        process-t (m/reduce (fn [r v] nil) nil process-flow)
        dispose!  (process-t #(info "process done" %)
                             #(error "process error " %))]
    dispose!))


(defn combined-flow [this]
  (:combined-flow this))

;; test orders

(defn send-test-order [oms account-id]
  (m/sp
   (m/? (create-order oms {:account/id account-id
                           :order-type :limit
                           :asset "__TEST"
                           :side :buy
                           :limit 110.30M
                           :qty 10000.0M
                           :campaign "test-order"
                           :label :buy-leg}))
   (m/? (create-order oms {:account/id account-id
                           :order-type :limit
                           :asset "__TEST"
                           :side :sell
                           :limit 110.32M
                           :qty 10000.0M
                           :campaign "test-order"
                           :label :sell-leg}))))

(defn create-order-rpc
  "Blocking entry point for flowy/clj-service (:mode :clj) RPC calls."
  [this order-details]
  (m/? (create-order this order-details)))

(defn send-test-order-rpc
  "Blocking entry point for flowy/clj-service (:mode :clj) RPC calls."
  [oms account-id]
  (m/? (send-test-order oms account-id)))