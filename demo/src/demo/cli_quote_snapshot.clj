(ns demo.cli-quote-snapshot
  (:require
   [clojure.pprint :refer [pprint]]
   [missionary.core :as m]
   [quanta.util.datahike]
   [quanta.asset.schema]
   [quanta.asset.seed]
   [quanta.blotter.oms.db]
   [quanta.quote.core :refer [create-quote-manager quote-snapshot]]))

(def snapshot-assets
  "One representative asset per quote source."
  {:random "__TEST"
   :spot-fx "EURUSD"
   :crypto "BTCUSDT.LF.BB"})

(defn- print-snapshot [kind asset quote]
  (println (str "\n--- " (name kind) " (" asset ") ---"))
  (if quote
    (pprint quote)
    (println "  (no quote within timeout)")))

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
                                   :log-file "log/quotes.txt"
                                   :ns-require ['fix-engine.quote.account
                                                'quanta.bybit.quote.account]})]
     (try
       (doseq [[kind asset] snapshot-assets]
         (print-snapshot kind asset (m/? (quote-snapshot qm timeout-ms asset))))
       (finally
         (quanta.util.datahike/db-stop db))))))

(defn start-cli
  "Usage: cd demo && clojure -X:cli-quote-snapshot"
  [_]
  (start!))
