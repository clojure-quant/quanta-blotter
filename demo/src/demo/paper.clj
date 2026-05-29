(ns demo.paper
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [missionary.core :as m]
   [quanta.blotter.protocol :as p]
   [quanta.blotter.paper.broker]
   [demo.util.orderflow-simulated-rdv :refer [create-orderflow-simulated-rdv]]
   [demo.util.update-printer :refer [create-orderupdate-printer]])
  (:import [missionary Cancelled]))

(defn load-demo-accounts
  []
  (-> "demo-accounts.edn" io/resource slurp edn/read-string))

(defn account-by-id [accounts id]
  (some #(when (= id (:account/id %)) %) accounts))


(defn start!
  "Start paper trade-account 1 fed by simulated orderflow for that account."
  []
  (let [account (account-by-id (load-demo-accounts) 1)
        {:keys [orderflow-simulated-rdv dispose-orderflow-simulated-rdv]} (create-orderflow-simulated-rdv)
        {:keys [orderupdate-rdv dispose-orderupdate-printer]} (create-orderupdate-printer)
        log println
        trade-account (p/create-trade-account account orderflow-simulated-rdv orderupdate-rdv log)
        dispose-account! (trade-account #(println "account done" %) #(println "account error" %))]
    {:dispose-order-puller dispose-orderflow-simulated-rdv
     :dispose-account dispose-account!
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
