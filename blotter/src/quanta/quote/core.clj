(ns quanta.quote.core
  (:require
   [clojure.java.io :as io]
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [datahike.api :as d]
   [missionary.core :as m]
   [modular.require :refer [require-namespaces]]
   [quanta.asset.datahike :refer [get-list]]
   [quanta.quote.account-manager :as am])
   (:import missionary.Cancelled)
  )

(defn- require-config-namespaces! [ns-require]
  (when (seq ns-require)
    (info "requiring namespaces:" (pr-str ns-require))
    (require-namespaces ns-require)))

(defn create-quote-manager [{:keys [quote-accounts-file db account-log-dir ns-require]
                             :or {account-log-dir "log/quote"
                                  ns-require []
                                  }}]
  (assert quote-accounts-file "quote-accounts-file is required")
  (assert db "db is required")
  (require-config-namespaces! ns-require)
  (.mkdirs (io/file account-log-dir))
  (let [am (am/create-account-manager {:account-log-dir account-log-dir})
        _ (am/add-edn-accounts am quote-accounts-file)]
    {:am am
     :db db}))

(defn calc-id
  [{:keys [db]} asset]
  (d/q '[:find ?account .
         :in $ ?symbol
         :where
         [?e :asset/symbol ?symbol]
         [?e :asset/default-quote-account ?account]]
       @db asset))

(defn asset-vec-dic-flow [{:keys [am] :as this} assets]
  (am/quote-list-dict-flow am (partial calc-id this) assets))

(defn asset-list-dic-flow [this asset-list-name]
  (let [{:keys [lists/name lists/asset]} (get-list (:db this) asset-list-name)
        ; #:lists{:name "default", :asset ["EURUSD" "USDJPY" "GBPUSD" "BTCUSDT.LF.BB" "ETHUSDT.LF.BB" "__TEST" "__TEST2"]}
       ]
    ;(println "asset-list " asset-list-name " has " (count asset) " assets")
   ; (println "asset-list " asset-list-name " assets: " (pr-str asset))
    (asset-vec-dic-flow this asset)))


(defn asset-list-flow-dict-flow [this asset-list-f]
  (m/ap
   (let [asset-list-name (m/?< asset-list-f)
         ;_ (println "asset-list-flow-dict-flow: asset-list-name: " asset-list-name)
         dict-flow (asset-list-dic-flow this asset-list-name)
         q (try (m/?> dict-flow)
                (catch Cancelled _
                  (m/amb)))]
     ;(println "asset-list-flow-dict-flow: q: " q)
     q
     )))


(defn create-quotelist-consumer [this asset-list-a]
  (let [quotelist-a (atom [])
        asset-list-f (m/watch asset-list-a)
        quote-dict-flow (asset-list-flow-dict-flow this asset-list-f)
        quote-processor   (m/reduce
                           (fn [_ v]
                             ;(println "QUOTELIST: " v)
                             (reset! quotelist-a v)
                             nil)
                           nil quote-dict-flow)
        dispose! (quote-processor #(info "quote-printer done " %)
                                  #(error "quote-printer CRASH " %))]
    {:dispose! dispose!
     :quotelist quotelist-a}))

;; quote task

(defn take-first-non-nil [f]
  ; flows dont implement deref
  (m/eduction
   (remove nil?)
   (take 1)
   f))

(defn current-v
  "gets the first non-nil value from the flow"
  [f]
  (m/reduce (fn [_r v]
              ;(println "current-v: " v)
              v) nil
            (take-first-non-nil f)))

(defn asset-quote-flow [this asset]
  (let [feed-id (calc-id this asset)]
    (am/quotes (:am this) feed-id asset)))

(defn quote-snapshot [this timeout-ms asset]
  (let [quote-f (asset-quote-flow this asset)
        quote-v (current-v quote-f)]
    (m/race quote-v (m/sleep timeout-ms nil))
    ))

