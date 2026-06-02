(ns demo.paper
  (:require
   [tick.core :as t]
   [clojure.edn :as edn]
   [missionary.core :as m]
   [quanta.blotter.protocol :as p]
   [quanta.blotter.paper.broker]
   [quanta.blotter.consolidator :refer [create-consolidator start-consolidator! stop-consolidator!]]
   [quanta.blotter.logger :refer [create-logger log stop-logger start-log-flow-to-logger]]
   [demo.util.orderflow-simulated-rdv :refer [create-orderflow-simulated-rdv]]
   [demo.util.update-printer :refer [create-orderupdate-printer]])
  (:import [missionary Cancelled]))


(defn load-demo-accounts
  []
  (-> "demo-accounts.edn" slurp edn/read-string))

(defn account-by-id [accounts id]
  (some #(when (= id (:account/id %)) %) accounts))


(defn start!
  "Start paper trade-account 1 fed by simulated orderflow for that account."
  []
  (let [;; logger
        l (create-logger "log/paper-log.txt" false)
        _ (log l {:type :paper/started :date (t/instant)})
        log-fn (partial log l)
        ; setup rdvs
        order-rdv (m/rdv)
        orderupdate-original-rdv (m/rdv)
        dispose-orderupdate-printer (create-orderupdate-printer orderupdate-original-rdv) ; uses original flow.
        ;; consolidator
        consolidator (create-consolidator {:order order-rdv :orderupdate orderupdate-original-rdv :log log-fn})
        _ (start-consolidator! consolidator)
        {:keys [order orderupdate]} (:channel consolidator)
        {:keys [combined-flow]} consolidator
        l-channel (create-logger "log/paper-order-orderupdate.txt" false)
        _ (log l-channel {:type :consolidator/started :date (t/instant)})
        dispose-flow-logger (start-log-flow-to-logger l-channel combined-flow)
        
        ;; trade account 
        account (account-by-id (load-demo-accounts) 1)
        trade-account (p/create-trade-account account order orderupdate log-fn)
        dispose-account! (trade-account #(println "account done" %) #(println "account error" %))
        
        ;; simulate orders
        dispose-orderflow-simulated-rdv (create-orderflow-simulated-rdv order-rdv)
        ]
    {;:dispose-logger (:dispose! l)
     :dispose-order-puller dispose-orderflow-simulated-rdv
     :dispose-account dispose-account!
     :dispose-pull-printer dispose-orderupdate-printer
     :dispose-flow-logger dispose-flow-logger
     }))


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
