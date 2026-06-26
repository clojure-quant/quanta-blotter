(ns quanta.blotter.oms.core
  (:require
   [missionary.core :as m]
   [nano-id.core :refer [nano-id]]
   [tick.core :as t]
   [quanta.missionary.logger :refer [create-logger log stop-logger start-log-flow-to-logger]]
   [quanta.blotter.consolidator :refer [create-consolidator start-consolidator!]]
   [quanta.blotter.oms.flow.campaign :refer [campaign-tagged-combined-flow]]
   [quanta.blotter.oms.validation.channel :refer [create-validation-channel start-validation-channel! stop-validation-channel!]]
   [quanta.blotter.oms.validation.schema :refer [validate-trader-message human-error-trader-message]]
   [quanta.blotter.account-manager :refer [create-account-manager start-account-manager add-edn-account add-edn-accounts]]
   [quanta.blotter.util-rdv :refer [create-rdv]]
   [quanta.blotter.oms.flow.trading-state :as trading-state]
   ;; side effects 
   [quanta.blotter.paper.broker]

   ))

(defn- create-validated-channel-stack
  "Consolidator (public) -> validator -> account manager."
  [log-fn order-rdv orderupdate-rdv]
  (let [consolidator (create-consolidator {:order order-rdv
                                           :orderupdate orderupdate-rdv
                                           :log log-fn})
        {:keys [order orderupdate]} (:channel consolidator)
        account-order-rdv (create-rdv "oms/account/order")
        account-orderupdate-rdv (create-rdv "oms/account/orderupdate")
        validator (create-validation-channel {:order account-order-rdv
                                              :orderupdate account-orderupdate-rdv
                                              :log log-fn}
                                             {:order order
                                              :orderupdate orderupdate})
        account-manager (create-account-manager account-order-rdv
                                                account-orderupdate-rdv
                                                log-fn)]
    {:order-rdv order-rdv
     :orderupdate-rdv orderupdate-rdv
     :consolidator consolidator
     :validator validator
     :account-manager account-manager}))

(defn create-order-manager [{:keys [log-file transaction-log-file validate? tag?]
                             :or {validate? true
                                  tag? true}}]
  (let [l (create-logger log-file false)
        _ (log l {:type :oms/started :date (t/instant)})
        log-fn (partial log l)
        log-transaction (create-logger transaction-log-file false)

        {:keys [order-rdv orderupdate-rdv consolidator validator account-manager]}
        (if validate?
          (let [order-rdv (create-rdv "oms/order")
                orderupdate-rdv (create-rdv "oms/orderupdate")]
            (create-validated-channel-stack log-fn order-rdv orderupdate-rdv))
          (let [order-rdv (create-rdv "oms/order")
                orderupdate-rdv (create-rdv "oms/orderupdate")
                consolidator (create-consolidator {:order order-rdv
                                                   :orderupdate orderupdate-rdv
                                                   :log log-fn})
                {:keys [order orderupdate]} (:channel consolidator)]
            {:order-rdv order-rdv
             :orderupdate-rdv orderupdate-rdv
             :consolidator consolidator
             :validator nil
             :account-manager (create-account-manager order orderupdate log-fn)}))
        {:keys [combined-flow]} consolidator
        combined-flow (if tag?
                        (m/stream (campaign-tagged-combined-flow combined-flow))
                        combined-flow)]
    {:log l
     :log-transaction log-transaction
     :validate? validate?
     :tag? tag?
     :dispose-a (atom nil)
     :order-rdv order-rdv
     :orderupdate-rdv orderupdate-rdv
     :consolidator consolidator
     :validator validator
     :account-manager account-manager
     :combined-flow combined-flow
     :trading-state (trading-state/create-trading-state! combined-flow)}))

(defn consume-orderupdate [r]
  (m/sp
   (loop []
     (m/? r)
     (recur))))

(defn start-order-manager!
  "Start paper trade-account 1 fed by simulated orderflow for that account."
  [{:keys [order-rdv orderupdate-rdv consolidator validator log-transaction account-manager combined-flow trading-state] :as this}]
  (let [dispose-transaction-logger (start-log-flow-to-logger log-transaction combined-flow)
        dispose-orderupdate-consumer!  ((consume-orderupdate orderupdate-rdv)
                                        #(println "orderupdate-consumer done " %)
                                        #(println "orderupdate-consumer error " %))
        dispose-validation! (when validator
                              (start-validation-channel! validator))
        dispose-consolidator! (start-consolidator! consolidator)
        dispose-account-manager! (start-account-manager account-manager)]
    (reset! (:dispose-a this)
            {:dispose-transaction-logger dispose-transaction-logger
             :dispose-orderupdate-consumer! dispose-orderupdate-consumer!
             :dispose-validation! dispose-validation!
             :dispose-consolidator! dispose-consolidator!
             :dispose-account-manager! dispose-account-manager!})
    (log log-transaction {:type :oms/started :date (t/instant)})
    (assoc this :trading-state trading-state)))

(defn stop-order-manager! [{:keys [dispose-a validator] :as this}]
  (when-let [d @dispose-a]
    (:dispose-account-manager! d)
    (when validator
      (stop-validation-channel! validator))
    (:dispose-consolidator! d)
    (:dispose-orderupdate-consumer! d)
    (:dispose-transaction-logger d)

    (reset! dispose-a nil))
  (dissoc this :trading-state))

(defn send-message
  "push a message on the OMS order channel. "
  [this message]
  (m/sp
   (assert (map? this) "this (oms) needs to be a map")
   (assert (:order-rdv this) "this (oms) needs to have an order-rdv")
   (if (validate-trader-message message)
     (let [message (assoc message :date (t/instant))] 
       (m/? (m/via m/blk (println "[OMS send-trader-message]: " message)))
       (m/? ((:order-rdv this) message))
       (m/? (m/via m/blk (println "[OMS send-trader-message] success: " message)))
       message)
     (throw (ex-info (human-error-trader-message message) {:message message})))))

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