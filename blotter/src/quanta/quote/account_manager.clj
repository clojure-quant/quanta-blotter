(ns quanta.quote.account-manager
  (:require
   [clojure.edn :as edn]
   [missionary.core :as m]
   [quanta.quote.protocol :as p]
   [quanta.quote.random] ; side effects
   ))


(defn- msg-flow
  "Missionary flow backed by a single downstream subscription (see flow-sender)."
  [!-a]
  (m/stream
   (m/observe
    (fn [!]
      (reset! !-a !)
      (fn []
        (reset! !-a nil))))))

(defn- flow-sender
  "Returns {:flow f :send s} where (s v) publishes v on f."
  []
  (let [!-a (atom nil)]
    {:flow (msg-flow !-a)
     :send (fn [v]
             (when-let [! @!-a]
               (! v)))}))

(defn add-account [state account]
  (let [account-id (:account/id account)
        subscription-a (atom #{})
        {:keys [flow send]} (flow-sender)]
    (assert account-id "account must have an :account/id")
    (assert (not (some? (get @(:accounts state) account-id))) "account/id is already in use.")
    (let [quote-account (p/create-quote-account account subscription-a send (:log state))
          dispose-account! (quote-account #(println "account done" %) #(println "account error" %))]
      (swap! (:accounts state) assoc account-id {:account/id account-id
                                                 :dispose-account dispose-account!
                                                 :flow flow
                                                 :subscription-a subscription-a}))))

(defn remove-account [state account-id]
  (let [account (get @(:accounts state) account-id)]
    (when account
      (:dispose-account account)
      (swap! (:accounts state) dissoc account-id))))


(defn create-account-manager [log]
  (let [accounts-a (atom {})]
    {:log log
     :accounts accounts-a}))

(defn get-account [this account-id]
  (get @(:accounts this) account-id))


(defn account-flow [this account-id]
  (when-let [account (get-account this account-id)]
    (:flow account)))

(defn account-asset-flow [this account-id asset]
  (when-let [account (get @(:accounts-a this) account-id)]
    (let [{:keys [subscription-a flow]} account]
      (m/ap
       (swap! subscription-a conj asset)))))


;; EDN

(defn load-edn-accounts  [edn-filename]
  (-> edn-filename slurp edn/read-string))

(defn account-by-id [accounts id]
  (some #(when (= id (:account/id %)) %) accounts))

(defn add-edn-account [state edn-filename account-id]
  (let [accounts (load-edn-accounts edn-filename)]
    (let [account (account-by-id accounts account-id)]
      (when account
        (println "adding account" account)
        (add-account state account)))))

(defn add-edn-accounts [state edn-filename]
  (let [accounts (load-edn-accounts edn-filename)]
    (doall
     (map #(add-account state %) accounts))))

