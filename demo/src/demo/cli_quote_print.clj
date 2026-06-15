(ns demo.cli-quote-print
  (:require
   [missionary.core :as m]
   [quanta.quote.account-manager :refer [create-account-manager add-edn-accounts get-account]]
   [quanta.blotter.logger :refer [create-logger log start-log-flow-to-logger]]
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
   (println "adding USDJPY subscription")
   (swap! subscription-a conj "USDJPY")
   
   (m/? (m/sleep 7000))
   (println "adding GBPUSD subscription")
   (swap! subscription-a conj "GBPUSD")

   (m/? (m/sleep 7000))
   (println "adding EURJPY subscription")
   (swap! subscription-a conj "EURJPY")
   
   (m/? (m/sleep 7000))
   (println "removing JPY subscriptions")
   (swap! subscription-a disj "EURJPY" "USDJPY")

   (m/? (m/sleep 7000))
   (println "adding NOK subscription")
   (swap! subscription-a conj "EURNOK")

   (m/? (m/sleep 7000))
   (println "only AUDUSD subscription")
   (reset! subscription-a #{"AUDUSD"})

   (m/? (m/sleep 7000))
   (println "subscription-changer done!")

   nil))


(defn start!
  "Mixed paper + FIX trade accounts via quanta-blotter account manager."
  []
  (let [l (create-logger "log/quotes.txt" false)
        log-fn (partial log l)

        am (create-account-manager log-fn)
        _ (add-edn-accounts am "demo-quote-accounts.edn")

        {:keys [flow subscription-a]} (get-account am 1)
        _ (reset! subscription-a #{"EURUSD" })

        printer (quote-printer flow)
        dispose-printer (printer #(println "quote-printer done" %)
                                 #(println "quote-printer CRASH" %))

        sub-changer (subscription-changer subscription-a)
        dispose-sub-changer (sub-changer #(println "sub-changer done: " %)
                                         #(println "sub-changer CRASH: " %))]
    {:dispose-printer dispose-printer
     :dispose-sub-changer dispose-sub-changer}))

(defn start-cli
  "Usage: cd demo && clojure -X:blotter-trade"
  [_]
  (start!)
  @(promise))

(comment
  (def ta (start!))
  (:dispose-account ta))
