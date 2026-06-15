(ns demo.cli-quote-api
  (:require
   [missionary.core :as m]
   [quanta.quote.account-manager :refer [create-account-manager add-edn-accounts get-account quotes]]
   [quanta.blotter.logger :refer [create-logger log start-log-flow-to-logger]]))

(defn quote-printer [f]
  (m/reduce
   (fn [s v]
     (println "QUOTE " v)
     nil)
   nil
   f))


(defn mix
  "Return a flow which is mixed by flows"
  ; will generate (count flows) processes, 
  ; so each mixed flow has its own process
  [& flows]
  (m/ap (m/?> (m/?> (count flows) (m/seed flows)))))

(defn start!
  "Mixed paper + FIX trade accounts via quanta-blotter account manager."
  []
  (let [l (create-logger "log/quotes.txt" false)
        log-fn (partial log l)

        am (create-account-manager log-fn)
        _ (add-edn-accounts am "demo-quote-accounts.edn")

        q1 (quotes am 1 "EURUSD")
        q2 (quotes am 1 "USDJPY")
        q3 (quotes am 1 "EURNOK")

        ; print 1
        printer (quote-printer q1)
        dispose-printer (printer #(println "1-quote-printer done " %) #(println "1-quote-printer CRASH " %))

        ; print mixed
        mixed (mix q1 q2 q3)
        printer2 (quote-printer mixed)
        dispose-printer2 (printer2 #(println "mixed-quote-printer done " %)
                                   #(println "mixed-quote-printer CRASH " %))

        ; stopper
        stop-mixed (m/sp (m/? (m/sleep 5000))
                         (println "stopping mixed")
                         (dispose-printer2))
        dispose-stopper (stop-mixed #(println "stop-mixed done" %) #(println "stop-mixed CRASH" %))]
    {:dispose-printer dispose-printer
     :dispose-printer2 dispose-printer2
     :dispose-stopper dispose-stopper}))

(defn start-cli
  "Usage: cd demo && clojure -X:blotter-trade"
  [_]
  (start!)
  @(promise))

(comment
  (def ta (start!))
  (:dispose-account ta))
