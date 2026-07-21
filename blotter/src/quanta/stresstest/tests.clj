(ns quanta.stresstest.tests
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [missionary.core :as m]
   [clojure.pprint :refer [print-table]]
   [nano-id.core :refer [nano-id]]
   [quanta.stresstest.tests.limit-buy-sell :refer [limit-buy-sell]]
   [quanta.stresstest.tests.limit-near-market-open-cancel :refer [limit-near-market-open-cancel]]
   [quanta.stresstest.tests.market-buy-close :refer [market-buy-close]]
   [quanta.stresstest.runner :refer [run]]))

(def account-config
  {1 [; paper broker with simulated/bybit/ctrader quotes
      ; limit buy + cancel
      :limit-near-market-open-cancel {:asset "__TEST" :qty 100M :offset-prct 20.0  :side :buy
                                      :expect {:fill-qty 0.0M
                                               :order-count 1  :active-order-count 0
                                               :position-count 0 :open-position-qty 0M}}
      :limit-near-market-open-cancel {:asset "BTCUSDT.LF.BB" :qty 0.1M :offset-prct 20.0  :side :buy
                                      :expect {:fill-qty 0.0M
                                               :order-count 1  :active-order-count 0
                                               :position-count 0 :open-position-qty 0M}}
      :limit-near-market-open-cancel {:asset "EURUSD" :qty 10000M :offset-prct 0.1 :side :buy
                                      :expect {:fill-qty 0.0M
                                               :order-count 1  :active-order-count 0
                                               :position-count 0 :open-position-qty 0M}}
      ; limit buy + sell (aggressive / fillable)
      :limit-buy-sell {:asset "__TEST" :qty 100M :offset-prct -20.0
                       :expect {:fill-qty 200.0M
                                :order-count 2  :active-order-count 0
                                :position-count 0 :open-position-qty 0M}}
      :limit-buy-sell {:asset "__TEST2" :qty 100M :offset-prct -20.0
                       :expect {:fill-qty 200.0M
                                :order-count 2  :active-order-count 0
                                :position-count 0 :open-position-qty 0M}}
      :limit-buy-sell {:asset "__TEST" :qty 100M :offset-prct -20.0
                       :expect {:fill-qty 200.0M
                                :order-count 2  :active-order-count 0
                                :position-count 0 :open-position-qty 0M}}
      :limit-buy-sell {:asset "__TEST2" :qty 100M :offset-prct -20.0
                       :expect {:fill-qty 200.0M
                                :order-count 2  :active-order-count 0
                                :position-count 0 :open-position-qty 0M}}
      ; :limit-buy-sell {:asset "BTCUSDT.LF.BB" :qty 0.1M :offset-prct -20.0
      ;                  :expect {:fill-qty 0.2M
      ;                           :order-count 2  :active-order-count 0
      ;                           :position-count 0 :open-position-qty 0M}}
      :limit-buy-sell {:asset "USDCAD" :qty 10000M :offset-prct -0.1
                       :expect {:fill-qty 20000.0M
                                :order-count 2  :active-order-count 0
                                :position-count 0 :open-position-qty 0M}}
      :limit-buy-sell {:asset "EURUSD" :qty 10000M :offset-prct -0.1
                       :expect {:fill-qty 20000.0M
                                :order-count 2  :active-order-count 0
                                :position-count 0 :open-position-qty 0M}}



      ; market buy/sell
      :market-buy-sell {:asset "__TEST" :qty 100M
                        :expect {:fill-qty 200.0M
                                 :order-count 2  :active-order-count 0
                                 :position-count 1 :open-position-qty 0M}}
      :market-buy-sell {:asset "BTCUSDT.LF.BB" :qty 0.1M
                        :expect {:fill-qty 0.2M
                                 :order-count 2  :active-order-count 0
                                 :position-count 1}}
      :market-buy-sell {:asset "EURUSD" :qty 10000M
                        :expect {:fill-qty 20000.0M
                                 :order-count 2 :active-order-count 0
                                 :position-count 1 :open-position-qty 0M}}]
   1000 [:limit-near-market-open-cancel {:asset "EURUSD" :qty 10000M :offset-prct 0.1 :side :buy
                                         :expect {:fill-qty 0.0M
                                                  :order-count 1 :active-order-count 0
                                                  :position-count 0 :open-position-qty 0M}}]
   2000 [:limit-near-market-open-cancel {:asset "BTCUSDT.LF.BB" :qty 0.2M :offset-prct 5.0 :side :buy
                                         :expect {:fill-qty 0.0M
                                                  :order-count 1 :active-order-count 0
                                                  :position-count 0 :open-position-qty 0M}}]})

 ; {:expect {:fill-qty 200.0M, :order-count 2, :active-order-count 0, :position-count 1, :open-position-qty 0M},
 ;  :result {:fill-qty 150M, :order-count 2, :active-order-count 1, :position-count 1, :open-position-qty 50M}}


(def algos {:market-buy-sell market-buy-close
            :limit-near-market-open-cancel limit-near-market-open-cancel
            :limit-buy-sell limit-buy-sell})


(defn run-test-task [oms account-id fn-kw opts]
  (let [test-fn (get algos fn-kw)
        campaign-id (str (name fn-kw) "-" (nano-id 8))
        runner-opts {:campaign-id campaign-id
                     :timeout-ms 30000}
        opts (assoc opts :account/id account-id)]
    (m/sp
     (warn "running" fn-kw "with" opts "campaign-id" campaign-id)
     (let [r (m/? (run oms runner-opts test-fn opts))
           r (assoc r :account-id account-id :fn-kw fn-kw :campaign-id campaign-id :asset (:asset opts))]
       (warn "result" r)
       r))))

(defn run-account-tests
  "Run stresstests for `account-id`. Optional `algo` keyword filters to that
   test category only, e.g. `(run-account-tests oms 1 :limit-buy-sell)`."
  ([oms account-id]
   (run-account-tests oms account-id nil))
  ([oms account-id algo]
   (when (and (some? algo) (not (keyword? algo)))
     (throw (ex-info "algo must be a keyword" {:algo algo})))
   (let [tests (get account-config account-id)
         tests (cond->> (partition 2 tests)
                 algo (filter (fn [[fn-kw _]] (= fn-kw algo))))
         results (atom [])
         run-tests-task (m/sp
                         (warn "running tests for account" account-id
                               (when algo (str "algo " algo)))
                         (m/? (m/sleep 10000)) ; be sure that quotefeeds and accounts are ready.
                         (loop [tests tests]
                           (when-let [[fn-kw opts] (first tests)]
                             (let [r (m/? (run-test-task oms account-id fn-kw opts))]
                               (swap! results conj r)
                               (when (next tests)
                                 (recur (rest tests)))))))]
     (m/? run-tests-task)
     (print-table @results))))


