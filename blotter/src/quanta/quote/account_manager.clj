(ns quanta.quote.account-manager
  (:require
   [clojure.edn :as edn]
   [tick.core :as t]
   [missionary.core :as m]
   [quanta.quote.protocol :as p]
   [quanta.quote.random] ; side effects
   )
  (:import missionary.Cancelled))


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
                                                 :lock (m/sem)
                                                 :dispose-account dispose-account!
                                                 :flow flow
                                                 :subscription-a subscription-a}))))

(defn quotes-impl [this account-id asset]
  (let [feed (get @(:accounts this) account-id)
        quote-flow (m/eduction
                    (filter #(= (:asset %) asset))
                    (:flow feed))]
    (m/stream
     (m/ap
      ;(println "quotes: adding asset to subscription-a" asset)
      (swap! (:subscription-a feed) conj asset)
      #_(m/with-lock (:lock feed))
      (try
        (let [q (m/?> quote-flow)]
          (assoc q :ts (t/instant)))

        (catch Cancelled ex
          ;(println "quotes: removing asset from subscription-a" asset)
                           ;(m/with-lock (:lock feed))
          (swap! (:subscription-a feed) disj asset)
          (throw ex)))))))

(def quotes (memoize quotes-impl))

(defn mix
  "Return a flow which is mixed by flows"
  ; will generate (count flows) processes, 
  ; so each mixed flow has its own process
  [& flows]
  (m/ap (m/?> (m/?> (count flows) (m/seed flows)))))

(defn quote-list-flow [this calc-id assets]
  (let [flows (map (fn [asset]
                     (quotes this (calc-id asset) asset))
                   assets)]
    (apply mix flows)))

(defn quote-list-dict-flow [this calc-id assets]
  (let [f (quote-list-flow this calc-id assets)]
    (m/reductions (fn [s v]
                    (assoc s (:asset v) v)) {} f)))



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

