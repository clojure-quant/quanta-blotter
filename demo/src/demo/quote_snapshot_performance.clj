(ns demo.quote-snapshot-performance
  (:require
   [clojure.pprint :refer [print-table]]
   [missionary.core :as m]
   [quanta.quote.core :as quote]))

(def assets
  ["__TEST" "__TEST" "__TEST"
   "__TEST2" "__TEST2" "__TEST2"
   "BTCUSDT.LF.BB" "BTCUSDT.LF.BB" "BTCUSDT.LF.BB"
   "USDCAD" "USDCAD" "USDCAD"
   "EURUSD" "EURUSD" "EURUSD"])

(def timeout-ms 5000)

(defn- timed-snapshot
  [qm asset]
  (let [t0 (System/nanoTime)
        q (m/? (quote/quote-snapshot qm timeout-ms asset))
        elapsed-ms (/ (double (- (System/nanoTime) t0)) 1e6)]
    {:asset asset
     :time-ms (if q
                (long (Math/round elapsed-ms))
                "timeout")}))

(defn run
  "Time sequential quote-snapshots for `assets`, print a table, then exit."
  [{:keys [running-system]}]
  (let [qm (:quote-manager running-system)
        rows (mapv #(timed-snapshot qm %) assets)]
    (print-table [:asset :time-ms] rows)))
