(ns quanta.quote.account-manager
  (:require
   [clojure.edn :as edn]
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [tick.core :as t]
   [missionary.core :as m]
   [quanta.missionary :refer [mix]]
   [quanta.missionary.logger :refer [create-logger log stop-logger]]
   [quanta.quote.protocol :as p])
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

(defn- account-log [state account-id]
  (if-let [dir (:account-log-dir state)]
    (let [logger (create-logger (str dir "/" account-id ".log") false)]
      {:log-fn (partial log logger)
       :logger logger})
    {:log-fn (fn [_] nil)
     :logger nil}))

(defn add-account [state account]
  (let [account-id (:account/id account)
        subscription-a (atom #{})
        {:keys [flow send]} (flow-sender)
        flow (m/stream (m/ap (let [data (m/?> flow)]
                               (when data
                                 (assoc data :account account-id)))))]
    (assert account-id "account must have an :account/id")
    (assert (not (some? (get @(:accounts state) account-id))) "account/id is already in use.")
    (let [{:keys [log-fn logger]} (account-log state account-id)
          quote-account (p/create-quote-account account subscription-a send log-fn)
          dispose-account! (quote-account #(info "account done" %) 
                                          #(error "account error" %))]
      (swap! (:accounts state) assoc account-id {:account/id account-id
                                                 :lock (m/sem)
                                                 :dispose-account dispose-account!
                                                 :flow flow
                                                 :subscription-a subscription-a
                                                 :logger logger}))))

(defn quotes-impl [this account-id asset]
  (let [_ (assert account-id (str "account-id is required for asset:" asset))
        feed (get @(:accounts this) account-id)
        _ (assert feed (str "feed not found for account-id:" account-id))
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
      (when-let [logger (:logger account)]
        (stop-logger logger))
      (swap! (:accounts state) dissoc account-id))))

(defn create-account-manager [{:keys [account-log-dir]}]
  (let [accounts-a (atom {})]
    {:account-log-dir account-log-dir
     :accounts accounts-a}))

(defn get-account [this account-id]
  (get @(:accounts this) account-id))

(defn account-flow [this account-id]
  (when-let [account (get-account this account-id)]
    (:flow account)))

(defn account-asset-flow [this account-id asset]
  (let [account (get @(:accounts-a this) account-id)
        _ (assert account (str "account not found: " account-id))
        {:keys [subscription-a flow]} account]
    (m/ap
     (swap! subscription-a conj asset))))

;; EDN

(defn load-edn-accounts  [edn-filename]
  (-> edn-filename slurp edn/read-string))

(defn account-by-id [accounts id]
  (some #(when (= id (:account/id %)) %) accounts))

(defn add-edn-account [state edn-filename account-id]
  (let [accounts (load-edn-accounts edn-filename)
        account (account-by-id accounts account-id)]
      (when account
        (info "adding account" account)
        (add-account state account))))

(defn add-edn-accounts [state edn-filename]
  (let [accounts (load-edn-accounts edn-filename)]
    (doall
     (map #(add-account state %) accounts))))

