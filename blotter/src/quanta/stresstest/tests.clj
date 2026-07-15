(ns quanta.stresstest.tests
  (:require
   [missionary.core :as m]
   [clojure.pprint :refer [print-table]]
   [nano-id.core :refer [nano-id]]
   [quanta.stresstest.tests.limit-near-market-open-cancel :refer [limit-near-market-open-cancel]]
   [quanta.stresstest.tests.market-buy-close :refer [market-buy-close]]
   [quanta.stresstest.runner :refer [run]]))

(def account-config
  {1 [; paper broker with simulated/bybit/ctrader quotes
      :limit-near-market-open-cancel {:asset "EURUSD" :qty 10000M :offset-prct 0.1 :side :buy
                                      :expect {:fill-qty 0.0M
                                               :order-count 1  :active-order-count 0
                                               :position-count 0 :open-position-qty 0M}}
      :limit-near-market-open-cancel {:asset "__TEST" :qty 100M :offset-prct 20.0  :side :buy
                                      :expect {:fill-qty 0.0M
                                               :order-count 1  :active-order-count 0
                                               :position-count 0 :open-position-qty 0M}}
      :limit-near-market-open-cancel {:asset "BTCUSDT.LF.BB" :qty 0.1M :offset-prct 20.0  :side :buy
                                      :expect {:fill-qty 0.0M
                                               :order-count 1  :active-order-count 0
                                               :position-count 0 :open-position-qty 0M}}
      :market-buy-sell {:asset "EURUSD" :qty 10000M
                        :expect {:fill-qty 20000.0M
                                 :order-count 2 :active-order-count 0
                                 :position-count 1 :open-position-qty 0M}}
      :market-buy-sell {:asset "__TEST" :qty 100M
                        :expect {:fill-qty 200.0M
                                 :order-count 2  :active-order-count 0
                                 :position-count 1 :open-position-qty 0M}}
      :market-buy-sell {:asset "BTCUSDT.LF.BB" :qty 0.1M
                        :expect {:fill-qty 0.2M
                                 :order-count 2  :active-order-count 0
                                 :position-count 1}}]
   1000 [:limit-near-market-open-cancel {:asset "EURUSD" :qty 10000M :offset-prct 0.1 :side :buy
                                         :expect {:fill-qty 0.0M
                                                  :order-count 1 :active-order-count 0
                                                  :position-count 0 :open-position-qty 0M}}]
   2000 [:limit-near-market-open-cancel {:asset "BTCUSDT.LF.BB" :qty 0.2M :offset-prct 5.0 :side :buy
                                         :expect {:fill-qty 0.0M
                                                  :order-count 1 :active-order-count 0
                                                  :position-count 0 :open-position-qty 0M}}]})

(def algos {:market-buy-sell market-buy-close
            :limit-near-market-open-cancel limit-near-market-open-cancel})


(defn run-test-task [oms account-id fn-kw opts]
  (let [test-fn (get algos fn-kw)
        campaign-id (str (name fn-kw) "-" (nano-id 8))
        runner-opts {:campaign-id campaign-id
                     :timeout-ms 10000}
        opts (assoc opts :account/id account-id)]
    (m/sp
     (println "running" fn-kw "with" opts "campaign-id" campaign-id)
     (let [r (try
               (let [{:keys [expect result] :as res} (m/? (run oms runner-opts test-fn opts))]
                 (println "res: " res)
                 (if (= expect result)
                   {:message "success"}
                   {:message "expected different result."}))
               (catch Exception e
                 (println "error running test" fn-kw
                          " opts " opts " campaign-id" campaign-id
                          "error: " (ex-message e))
                 {:message (ex-message e)}))
           r (assoc r :account-id account-id :fn-kw fn-kw :campaign-id campaign-id :asset (:asset opts))]
       (println "result" r)
       r))))

(defn run-account-tests [oms account-id]
  (let [tests (get account-config account-id)
        tests (partition 2 tests)
        results (atom [])
        run-tests-task (m/sp
                        (loop [tests tests]
                          (let [[fn-kw opts] (first tests)
                                r (m/? (run-test-task oms account-id fn-kw opts))]
                            (swap! results conj r)
                            (when (next tests)
                              (recur (rest tests))))))]
    (m/? run-tests-task)
    (print-table @results)))


