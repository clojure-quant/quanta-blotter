(ns quanta.blotter.oms.core
  (:require
   [missionary.core :as m]
   [nano-id.core :refer [nano-id]]
   [tick.core :as t]
   [quanta.blotter.logger :refer [create-logger log stop-logger start-log-flow-to-logger]]
   [quanta.blotter.consolidator :refer [create-consolidator start-consolidator!]]
   [quanta.blotter.account-manager :refer [create-account-manager start-account-manager add-edn-account add-edn-accounts]]
   [quanta.blotter.paper.broker]))

(defn create-order-manager [{:keys [log-file transaction-log-file]}]
  (let [;; logger
        l (create-logger log-file false)
        _ (log l {:type :oms/started :date (t/instant)})
        log-fn (partial log l)
        ; setup rdvs
        order-rdv (m/rdv)
        orderupdate-rdv (m/rdv)
        ;; consolidator
        consolidator (create-consolidator {:order order-rdv :orderupdate orderupdate-rdv :log log-fn})
        {:keys [order orderupdate]} (:channel consolidator)
        ;; transaction log
        log-transaction (create-logger transaction-log-file false)
        ; account manager
        account-manager (create-account-manager order orderupdate log-fn)]
    {:order-rdv order-rdv
     :orderupdate-rdv orderupdate-rdv
     :consolidator consolidator
     :log-transaction log-transaction
     :account-manager account-manager
     :dispose-a (atom nil)}))


(defn consume-orderupdate [r]
  (m/sp
   (loop []
     (m/? r)
     (recur))))

(defn start-order-manager!
  "Start paper trade-account 1 fed by simulated orderflow for that account."
  [{:keys [order-rdv orderupdate-rdv consolidator log-transaction account-manager] :as this}]
  (let [{:keys [combined-flow]} consolidator
        dispose-transaction-logger (start-log-flow-to-logger log-transaction combined-flow)
        dispose-orderupdate-consumer!  ((consume-orderupdate orderupdate-rdv)
                                        #(println "orderupdate-consumer done " %)
                                        #(println "orderupdate-consumer error " %))
        dispose-consolidator! (start-consolidator! consolidator)
        dispose-account-manager! (start-account-manager account-manager)]
    (reset! (:dispose-a this)
            {:dispose-transaction-logger dispose-transaction-logger
             :dispose-orderupdate-consumer! dispose-orderupdate-consumer!
             :dispose-consolidator! dispose-consolidator!
             :dispose-account-manager! dispose-account-manager!})
    (log log-transaction {:type :oms/started :date (t/instant)})))


(defn stop-order-manager! [{:keys [dispose-a] :as this}]
  (when-let [d @dispose-a]
    (:dispose-account-manager! d)
    (:dispose-consolidator! d)
    (:dispose-orderupdate-consumer! d)
    (:dispose-transaction-logger d)
    (reset! dispose-a nil)))

(defn create-limit-order
  "Create a limit order and push it on the OMS order channel."
  [this {:keys [asset side qty limit order-id broker]
         :as order-details}]
  (m/sp
   (assert (map? order-details) "order-details must be a map")
   (assert (map? this) "this (oms) needs to be a map")
   (assert (:order-rdv this) "this (oms) needs to have an order-rdv")
   
   (let [order (-> order-details
                   (assoc :type :trader/new-order)
                   (cond-> (not order-id) (assoc :order-id (nano-id 6))))]
     (println "create limit order: " order)
     (m/? ((:order-rdv this) order))
     (println "create limit order success! order: " order)
     order)))


(defn combined-flow [this]
  (get-in this [:consolidator :combined-flow]))


(defn send-test-order [oms account-id]
  (m/sp 
   (m/? (create-limit-order oms {:account/id account-id
                                 :asset "__TEST"
                                 :side :buy
                                 :limit 110.30M
                                 :qty 10000.0M}))
   (m/? (create-limit-order oms {:account/id account-id
                                 :asset "__TEST"
                                 :side :sell
                                 :limit 110.32M
                                 :qty 10000.0M}))))