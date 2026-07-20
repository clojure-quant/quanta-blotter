(ns quanta.blotter.oms.trader
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [missionary.core :as m]
   [quanta.blotter.oms.db :as db]))

(defn db-lookup [conn account-id]
  (some-> (db/account-by-id conn account-id)
          (select-keys [:account/trader :account/name])))

(defn lookup-account [{:keys [db cache]} account-id]
  (let [account (get @cache account-id)]
    (if account
      account
      (let [account-data (db-lookup db account-id)]
        (swap! cache assoc account-id account-data)
        account-data))))


(defn trader-tagger-flow [db state-f]
  (let [this  {:db db
               :cache (atom {})}
        amend-position (fn [{:keys [:position/account] :as position}]
                         (let [account (lookup-account this account)]
                           (if account
                             (assoc position
                                    :position/trader (:account/trader account)
                                    :position/account-name (:account/name account))
                             position)))
        amend-order (fn [{:keys [order/account-id] :as order}]
                      (let [account (lookup-account this account-id)]
                        (if account
                          (assoc order
                                 :order/trader (:account/trader account)
                                 :order/account-name (:account/name account))
                          order)))
        amended-f (m/ap (let [{:keys [open-positions working-orders]} (m/?> state-f)
                              amended-positions (mapv amend-position open-positions)
                              amended-orders (mapv amend-order working-orders)
                              data {:open-positions amended-positions
                                    :working-orders amended-orders}]

                          data))]
    amended-f))

(defn start-trader-tagger [db trading-state-a]
  (let [state-f (m/watch trading-state-a)
        tt-f (trader-tagger-flow db state-f)
        trading-state-trader (atom {:open-positions [] :working-orders []})
        tt-a-f (m/ap (let [data (m/?> tt-f)]
                       (reset! trading-state-trader data)
                       data))
        t (m/reduce (fn [_r _v] nil) nil tt-a-f)]
    {:dispose! (t #(info "trader-tagger done" %)
                  #(error "trader-tagger error" %))
     :trading-state-trader trading-state-trader}))