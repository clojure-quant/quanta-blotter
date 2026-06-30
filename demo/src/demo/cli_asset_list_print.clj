(ns demo.cli-asset-list-print
  (:require
   [missionary.core :as m]
   [quanta.util.datahike]
   [quanta.asset.schema]
   [quanta.asset.seed]
   [quanta.blotter.oms.db]
   [quanta.quote.core :refer [create-quote-manager create-quotelist-consumer]]
   ))

(defn quote-printer [f]
  (m/reduce
   (fn [s v]
     (println "QUOTE" v)
     nil)
   nil
   f))

(defn subscription-changer [subscription-a]
  (m/sp

   (m/? (m/sleep 7000))
   (println "asset-list-print: subscribing to crypto")
   (reset! subscription-a "crypto")

   (m/? (m/sleep 7000))
   (println "asset-list-print: subscribing to spot-fx")
   (reset! subscription-a "spot-fx")

   (m/? (m/sleep 7000))
   (println "asset-list-print: subscribing to default (mix of crypto/spot/random)")
   (reset! subscription-a "default")
   
   (m/? (m/sleep 7000))
   (println "subscription-changer done!")

   nil))

(defn start! []
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
                                               'quanta.bybit.quote.account]
                                  })
        subscription-a (atom "test")
        {:keys [quotelist-a dispose!]} (create-quotelist-consumer qm subscription-a)
        printer (quote-printer (m/watch quotelist-a))
        dispose-printer (printer #(println "quote-printer done" %)
                                 #(println "quote-printer CRASH" %))

        sub-changer (subscription-changer subscription-a)
        ;dispose-sub-changer (sub-changer #(println "sub-changer done: " %) #(println "sub-changer CRASH: " %))
        ]
    {:dispose-printer dispose-printer
     ;:dispose-sub-changer dispose-sub-changer
     :dispose-consumer dispose!
     }))

(defn start-cli [_]
  (start!)
  @(promise))

