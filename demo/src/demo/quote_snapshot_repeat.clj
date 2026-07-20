(ns demo.quote-snapshot-repeat
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [missionary.core :as m]
   [quanta.util.datahike]
   [quanta.asset.schema]
   [quanta.asset.seed]
   [quanta.blotter.oms.db]
   [quanta.quote.core :refer [create-quote-manager quote-snapshot]]))

(def asset "EURUSD")

(defn- timed-snapshot-task [qm timeout-ms]
  (m/sp
   (let [t0 (System/nanoTime)
         quote (m/? (quote-snapshot qm timeout-ms asset))
         elapsed-ms (/ (double (- (System/nanoTime) t0)) 1e6)]
     {:quote quote
      :elapsed-ms elapsed-ms})))

(defn- print-result [label {:keys [quote elapsed-ms]}]
  (println (format "\n--- %s — %.1f ms ---" label elapsed-ms))
  (if quote
    (pprint quote)
    (println "  (no quote within timeout)")))

(defn- run-parallel [qm timeout-ms labels]
  (let [t0 (System/nanoTime)
        results (m/? (apply m/join vector
                            (map (fn [_] (timed-snapshot-task qm timeout-ms)) labels)))
        wall-ms (/ (double (- (System/nanoTime) t0)) 1e6)]
    (println (format "\n=== parallel %s — wall %.1f ms ==="
                     (str/join "+" labels) wall-ms))
    (doseq [[label result] (map vector labels results)]
      (print-result label result))))

(defn start!
  ([] (start! {}))
  ([{:keys [timeout-ms] :or {timeout-ms 15000}}]
   (let [db (quanta.util.datahike/db-start
             {:schema (concat
                       quanta.blotter.oms.db/schema
                       quanta.asset.schema/asset)
              :db-path "asset-db"
              :seed-fn [(quanta.asset.seed/seed-edn-assets-fn "demo-assets.edn")
                        (quanta.asset.seed/seed-edn-lists-fn "demo-lists")]})
         qm (create-quote-manager {:db db
                                   :quote-accounts-file "demo-quote-accounts.edn"
                                   :ns-require ['quanta.market-sim.quote-random
                                                'fix-engine.quote.account
                                                'quanta.bybit.quote.account]})]
     (try
       (println "EURUSD quote snapshots: 1 standalone, then 2||3, then 4||5")
       (print-result "request 1 (standalone)"
                     (m/? (timed-snapshot-task qm timeout-ms)))
       (run-parallel qm timeout-ms ["request 2" "request 3"])
       (run-parallel qm timeout-ms ["request 4" "request 5"])
       (finally
         (quanta.util.datahike/db-stop db))))))

(defn start-cli
  "Usage: cd demo && clojure -X:quote-snapshot-repeat"
  [_]
  (start!))
