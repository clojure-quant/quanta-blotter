(ns quanta.blotter.account-manager
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [clojure.edn :as edn]
   [datahike.api :as d]
   [missionary.core :as m]
   [quanta.missionary.logger :refer [create-logger log stop-logger]]
   [quanta.blotter.protocol :as p]
   [quanta.blotter.util-rdv :refer [create-rdv]] 
   [quanta.blotter.oms.db :as db]
   ))

(defn- account-log [state account-id]
  (if-let [dir (:account-log-dir state)]
    (let [logger (create-logger (str dir "/" account-id ".log") false)]
      {:log-fn (partial log logger)
       :logger logger})
    {:log-fn (:log state)
     :logger nil}))

(defn add-account [state account]
  (let [account-id (:account/id account)
        account-order-rdf (create-rdv (str "account/" account-id "/order"))
        account-orderupdate-rdf (create-rdv (str "account/" account-id "/orderupdate"))]
    (assert account-id "account must have an :account/id")
    (assert (not (some? (get @(:accounts state) account-id))) "account/id is already in use.")
    (let [{:keys [log-fn logger]} (account-log state account-id)
          trade-account (p/create-trade-account (:ctx state) account account-order-rdf account-orderupdate-rdf log-fn)
          dispose-account! (trade-account #(println "account done" %) #(println "account error" %))]
      (swap! (:accounts state) assoc account-id {:account/id account-id
                                                 :dispose-account dispose-account!
                                                 :account-order-rdf account-order-rdf
                                                 :account-orderupdate-rdf account-orderupdate-rdf
                                                 :logger logger}))))
(defn remove-account [state account-id]
  (let [account (get @(:accounts state) account-id)]
    (when account
      (:dispose-account account)
      (when-let [logger (:logger account)]
        (stop-logger logger))
      (swap! (:accounts state) dissoc account-id))))

(defn create-account-manager [ctx orderflow-rdv orderupdate-rdv {:keys [log account-log-dir]}]
  (let [accounts-a (atom {})]
    {:ctx ctx
     :log log
     :account-log-dir account-log-dir
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

(defn add-enabled-db-accounts [state conn]
  (doall
   (map #(add-account state %)
        (db/all-enabled-accounts conn))))

(defn- asset-list-id [conn list-name]
  (d/q '[:find ?id .
         :in $ ?list-name
         :where [?id :lists/name ?list-name]]
       @conn list-name))

(defn- resolve-asset-list! [conn list-name]
  (or (asset-list-id conn list-name)
      (throw (ex-info (str "asset-list " list-name " not found") {:list-name list-name}))))

(defn seed-edn-accounts [edn-filename]
  (fn [conn]
    (let [accounts (load-edn-accounts edn-filename)]
      (doseq [account accounts]
        (let [list-name (:account/asset-list account)
              asset-list-ref (resolve-asset-list! conn list-name)]
          (info "seeding account" (:account/id account))
          (db/create-account conn (select-keys account [:account/id :account/trader :account/api]))
          (db/update-account conn (merge (select-keys account [:account/id :account/notes
                                                               :account/settings :account/name
                                                               :account/balance :account/enabled
                                                               :account/currency])
                                         {:account/asset-list asset-list-ref}))
          ;(db/enable-account conn (:account/id account) true)
          )))))