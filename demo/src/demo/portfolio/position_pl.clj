(ns demo.portfolio.position-pl
  "Show average-cost position P/L and entry/exit averages.

  Run from the demo directory:
    clojure -M -e \"(require 'demo.portfolio.position-pl) (demo.portfolio.position-pl/run-demo!)\""
  (:require
   [clojure.pprint :refer [print-table]]
   [quanta.blotter.oms.portfolio.open-position :as open-position]))

(defn- fill-msg [account asset side qty price]
  {:fill/account-id account
   :fill/asset asset
   :fill/side side
   :fill/qty qty
   :fill/price price})

(defn- emissions [fills]
  (let [msgs (map (partial apply fill-msg) fills)]
    (second
     (reduce
      (fn [[positions outs] fill]
        (let [{:keys [open-position positions-change]}
              (open-position/process-trade positions fill)]
          [open-position (into outs positions-change)]))
      [{} []]
      msgs))))

(defn- row [scenario step pos]
  (let [side (:position/side pos)
        entry (:position/average-entry-price pos)
        realized (:position/realized-pl pos)
        qty-entry (:position/qty-entry pos)
        qty-exit (:position/qty-exit pos)
        qty-open (:position/qty-open pos)
        avg-exit (:position/avg-exit-price pos)
        formula-pl (when (and (pos? qty-exit) entry avg-exit)
                     (case side
                       :long (* qty-exit (- avg-exit entry))
                       :short (* qty-exit (- entry avg-exit))
                       nil))]
    {:scenario scenario
     :step step
     :side (name (or side :flat))
     :open (open-position/position-open? pos)
     :qty-entry qty-entry
     :qty-exit qty-exit
     :qty-open qty-open
     :avg-entry entry
     :realized-pl realized
     :avg-exit avg-exit
     :formula-pl formula-pl
     :match? (= realized formula-pl)}))

(defn- analyze-scenario [label fills]
  (map-indexed
   (fn [i pos] (row label (inc i) pos))
   (emissions fills)))

(def scenarios
  {"partial-multi-lot"
   [[1 "X" :buy 50.0 10.0]
    [1 "X" :buy 50.0 12.0]
    [1 "X" :sell 60.0 15.0]]

   "full-close-simple"
   [[1 "X" :buy 100.0 10.0]
    [1 "X" :sell 100.0 15.0]]

   "full-close-multi-lot"
   [[1 "X" :buy 50.0 10.0]
    [1 "X" :buy 50.0 12.0]
    [1 "X" :sell 100.0 15.0]]

   "flip"
   [[1 "X" :buy 100.0 10.0]
    [1 "X" :sell 110.0 11.0]]})

(defn run-demo!
  "Print average-cost tables for scripted fill sequences."
  [& _]
  (doseq [[name fills] scenarios]
    (println \newline "===" name "===")
    (print-table [:scenario :step :side :open :qty-entry :qty-exit :qty-open
                  :avg-entry :realized-pl :avg-exit :formula-pl :match?]
                 (analyze-scenario name fills)))
  (flush))

(comment
  (run-demo!))
