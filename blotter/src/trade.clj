(ns quanta.market.trade
  (:require
   [clojure.string :as str]
   [missionary.core :as m]
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [quanta.market.protocol :as p]
   [quanta.market.util :refer [mix start-logging]]
   ; bring default implementations into scope:
   [quanta.market.broker.bybit.trade]))

(defn- get-tradeaccount-order [{:keys [tradeaccounts] :as _this} {:keys [account]}]
  (get tradeaccounts account))

(defn- mix-flows [flow-fn tradeaccounts]
  (info "mix flows")
  (let [accounts (vals tradeaccounts)
        _ (info "accounts: " accounts)
        flows (map flow-fn accounts)]
    (info "mixing flows..")
    (apply mix flows)))

(defrecord trade-manager [tradeaccounts log-dir]
  p/trade-action
  (trade-action-flow [this]
    (info "mixing trade-action flows..")
    (mix-flows p/trade-action-flow tradeaccounts))
  (trade-action-msg-flow [this]
    (info "mixing trade-action-msg flows..")
    (mix-flows p/trade-action-msg-flow tradeaccounts))
  (order-create! [this order]
    (when-let [ta (get-tradeaccount-order this order)]
      (info "trade-account: " ta)
      (p/order-create! ta order)))
  (order-cancel! [this order-cancel]
    (if-let [ta (get-tradeaccount-order this order-cancel)]
      (p/order-cancel! ta order-cancel)))
  ; update
  p/trade-update
  (orderupdate-flow [this]
    (info "mixing orderupdate-flow flows..")
    (mix-flows p/orderupdate-flow tradeaccounts))
  (orderupdate-msg-flow [this]
    (info "mixing orderupdate-msg flows..")
    (mix-flows p/orderupdate-msg-flow tradeaccounts))
  ; account
  p/trade-account
  (account-flow [this]
    (info "mixing account flows..")
    (mix-flows p/account-flow tradeaccounts))
  (account-msg-flow [this]
    (info "mixing account-msg flows..")
    (mix-flows p/account-msg-flow tradeaccounts)))

(defn account-log-filename [log-dir id ext]
  (let [account-str  (-> (str "account-" id ext ".txt")
                         (str/replace #":" "")
                         (str/replace #"/" "_"))]
    (str log-dir account-str)))

(defn create-account [log-dir [id opts]]
  (let [account (p/create-tradeaccount (assoc opts :account-id id))
        log-filename (account-log-filename log-dir id "")
        log-msg-filename (account-log-filename log-dir id "-msg")]
    (when log-dir
      (info "logging " id " to file: " log-filename)
      (start-logging log-filename (p/account-flow account))
      (start-logging log-msg-filename (p/account-msg-flow account)))
    [id account]))

(defn create-accounts [accounts log-dir]
  (->> accounts
       (map #(create-account log-dir %))
       (into {})))

(defn trade-manager-start [accounts log-dir]
  (let [tradefeeds (create-accounts accounts log-dir)]
    (trade-manager. tradefeeds log-dir)))

(defn trade-manager-stop [this]
  ;(p/stop-trade this)
  ;
  )

(comment
  (account-log-filename "./data/" :rene/test1-bybit)

 ; 
  )