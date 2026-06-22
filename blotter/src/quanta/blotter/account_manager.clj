(ns quanta.blotter.account-manager
  (:require
   [clojure.edn :as edn]
   [missionary.core :as m]
   [quanta.blotter.protocol :as p]
   [quanta.blotter.util-rdv :refer [create-rdv]]
   [quanta.blotter.paper.broker]))

(defn add-account [state account]
  (let [account-id (:account/id account)
        account-order-rdf (create-rdv (str "account/" account-id "/order"))
        account-orderupdate-rdf (create-rdv (str "account/" account-id "/orderupdate"))]
    (assert account-id "account must have an :account/id")
    (assert (not (some? (get @(:accounts state) account-id))) "account/id is already in use.")
    (let [trade-account (p/create-trade-account account account-order-rdf account-orderupdate-rdf (:log state))
          dispose-account! (trade-account #(println "account done" %) #(println "account error" %))]
      (swap! (:accounts state) assoc account-id {:account/id account-id
                                                 :dispose-account dispose-account!
                                                 :account-order-rdf account-order-rdf
                                                 :account-orderupdate-rdf account-orderupdate-rdf}))))
(defn remove-account [state account-id]
  (let [account (get @(:accounts state) account-id)]
    (when account
      (:dispose-account account)
      (swap! (:accounts state) dissoc account-id))))

(defn create-account-manager [orderflow-rdv orderupdate-rdv log]
  (let [accounts-a (atom {})]
    {:log log
     :orderflow-rdv orderflow-rdv
     :orderupdate-rdv orderupdate-rdv
     :accounts accounts-a
     :account-change-f (let [f (m/watch accounts-a)]
                         (m/ap
                          (let [data (m/?> f)]

                            ::account-change)))
     :dispose! (atom nil)}))

(defn wait-for-account-change [state]
  (let [next-change-f (m/eduction (drop 1)
                                  (take 1)
                                  (:account-change-f state))
        waiting-f (m/ap
                   (let [v (m/?> next-change-f)]
                     (m/? (m/via m/blk (println "** account change: " v)))
                     v))]
    (m/reduce (fn [_r v] v) nil waiting-f)))

(defn read-account-orderupdate [account]
  (m/sp
   (let [data-in (m/? (:account-orderupdate-rdf account))]
     (assoc data-in :account/id (:account/id account)))))

(defn consolidate-accounts-orderupdate [state]
  (m/sp
   (loop []
     (let [accounts @(:accounts state)
           inputs (map read-account-orderupdate (vals accounts))
           data  (m/? (apply m/race (conj inputs (wait-for-account-change state))))]
       (if (= data ::account-change)
         (m/? (m/via m/blk (println "consolidate account change!")))
         (m/? ((:orderupdate-rdv state) data)))
       (recur)))))

(defn forward-new-order-to-account [state]
  (m/sp
   (loop []
     (let [data-in (m/? (:orderflow-rdv state))
           account-id (:account/id data-in)]
       (if account-id
         (let [account (get @(:accounts state) account-id)]
           (when account
             (m/? ((:account-order-rdf account) data-in))))
         (m/? (m/via m/blk (println "ignoring msg without account/id:")))))
     (recur))))

(defn multiplex-t [state]
  (m/join concat
          (consolidate-accounts-orderupdate state)
          (forward-new-order-to-account state)))

(defn start-account-manager [state]
  (reset! (:dispose! state)
          ((multiplex-t state) #(println "multiplex done" %) #(println "multiplex error" %))))

;; EDN

(defn load-edn-accounts  [edn-filename]
  (-> edn-filename slurp edn/read-string))

(defn account-by-id [accounts id]
  (some #(when (= id (:account/id %)) %) accounts))

(defn add-edn-account [state edn-filename account-id]
  (let [accounts (load-edn-accounts edn-filename)]
    (let [account (account-by-id accounts account-id)]
      (when account
        (add-account state account)))))

(defn add-edn-accounts [state edn-filename]
  (let [accounts (load-edn-accounts edn-filename)]
    (doall
     (map #(add-account state %) accounts))))

