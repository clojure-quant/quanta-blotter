(ns demo.account-manager-paper
  (:require
   [missionary.core :as m]
   [tick.core :as t]
   [quanta.blotter.paper.broker] ;; side effect: brings in paper broker implementation
   [quanta.blotter.account-manager :refer [create-account-manager start-account-manager add-edn-accounts]]
   [quanta.blotter.consolidator :refer [create-consolidator start-consolidator!]]
   [quanta.missionary.logger :refer [create-logger log start-log-flow-to-logger]]
   [quanta.blotter.util :refer [push-flow-to-rdv]]
   [demo.util.orderflow-simulated :refer [demo-order-action-flow]]
   [demo.util.update-printer :refer [create-orderupdate-printer]]))

(defn start!
  "Start paper trade-account 1 fed by simulated orderflow for that account."
  []
  (let [;; logger
        l (create-logger "log/paper-account-manager.txt" false)
        _ (log l {:type :paper/started :date (t/instant)})
        log-fn (partial log l)
        ; setup rdvs
        order-rdv (m/rdv)
        orderupdate-rdv (m/rdv)
        dispose-orderupdate-printer (create-orderupdate-printer orderupdate-rdv) ; uses original flow.        
        ;; consolidator
        consolidator (create-consolidator {:order order-rdv :orderupdate orderupdate-rdv :log log-fn})
        _ (start-consolidator! consolidator)
        {:keys [order orderupdate]} (:channel consolidator)
        {:keys [combined-flow]} consolidator
        l-channel (create-logger "log/paper-account-manager-order-orderupdate.txt" false)
        _ (log l-channel {:type :consolidator/started :date (t/instant)})
        dispose-flow-logger (start-log-flow-to-logger l-channel combined-flow)
        ;; account-manager
        am (create-account-manager order orderupdate {:log log-fn})
        ;_ (add-edn-account am "demo-accounts.edn" 1)
        ;_ (add-edn-account am "demo-accounts.edn" 2)
        _ (add-edn-accounts am "demo-accounts.edn")
        dispose! (start-account-manager am)
        ;; simulate orders
        dispose-orderflow-simulated (push-flow-to-rdv order-rdv demo-order-action-flow)]
    {:dispose-orderflow-simulated dispose-orderflow-simulated
     :dispose-account dispose!
     :dispose-pull-printer dispose-orderupdate-printer
     :dispose-flow-logger dispose-flow-logger}))

(comment
  (def ta (start!))
  (println "disposing account")
  (:dispose-account ta)
  (println "disposing order puller")
  (:dispose-order-puller ta)
  (println "disposing pull printer")
  (:dispose-pull-printer ta)

; 
  )
