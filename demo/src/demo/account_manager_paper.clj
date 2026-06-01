(ns demo.account-manager-paper
  (:require
   [missionary.core :as m]
   [quanta.blotter.paper.broker] ;; side effect: brings in paper broker implementation
   [quanta.blotter.account-manager :refer [create-account-manager start-account-manager add-edn-account add-edn-accounts]]
   [quanta.blotter.logger :refer [create-logger log stop-logger]]
   [demo.util.orderflow-simulated-rdv :refer [create-orderflow-simulated-rdv]]
   [demo.util.update-printer :refer [create-orderupdate-printer]])
  (:import [missionary Cancelled]))


(defn start!
  "Start paper trade-account 1 fed by simulated orderflow for that account."
  []
  (let [;; logger
        l (create-logger "account-manager-paper.txt")
        log (partial log l)
        ;; trading-flows
        {:keys [orderflow-simulated-rdv dispose-orderflow-simulated-rdv]} (create-orderflow-simulated-rdv)
        {:keys [orderupdate-rdv dispose-orderupdate-printer]} (create-orderupdate-printer)
        ;; account-manager
        am (create-account-manager orderflow-simulated-rdv orderupdate-rdv log)
        ;_ (add-edn-account am "demo-accounts.edn" 1)
        ;_ (add-edn-account am "demo-accounts.edn" 2)
        _ (add-edn-accounts am "demo-accounts.edn")
        dispose! (start-account-manager am)]
    {:dispose-order-puller dispose-orderflow-simulated-rdv
     :dispose-account dispose!
     :dispose-pull-printer dispose-orderupdate-printer}))


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
