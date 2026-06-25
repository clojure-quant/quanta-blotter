(ns demo.cli-quote-list
  (:require
   [missionary.core :as m]
   [quanta.quote.account-manager :refer [create-account-manager add-edn-accounts quote-list-dict-flow]]
   [quanta.missionary.logger :refer [create-logger log start-log-flow-to-logger]]))

(defn quote-printer [f]
  (m/reduce
   (fn [s v]
     (println "QUOTELIST: " v)
     nil)
   nil
   f))

(defn start!
  "Mixed paper + FIX trade accounts via quanta-blotter account manager."
  []
  (let [l (create-logger "log/quotes.txt" false)
        log-fn (partial log l)

        am (create-account-manager log-fn)
        _ (add-edn-accounts am "demo-quote-accounts.edn")

        ql (quote-list-dict-flow am (fn [_asset] 1)
                                 ["EURUSD" "USDJPY" "EURNOK"])

        printer (quote-printer ql)
        dispose-printer (printer #(println "1-quote-printer done " %) #(println "1-quote-printer CRASH " %))]
    {:dispose-printer dispose-printer}))

(defn start-cli
  "Usage: cd demo && clojure -X:blotter-trade"
  [_]
  (start!)
  @(promise))

(comment
  (def ta (start!))
  (:dispose-account ta))
