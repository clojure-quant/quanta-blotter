(ns demo.quote-snapshot-performance
  (:require
   [clojure.pprint :refer [print-table]]
   [missionary.core :as m]
   [quanta.quote.core :as quote]))

(def unique-assets
  ["__TEST"
   "__TEST2"
   "BTCUSDT.LF.BB"
   "BTCUSDT.S.BB"
   "ETHUSDT.LF.BB"
   "ETHUSDT.S.BB"
   "USDCAD"
   "EURUSD"
   "USDJPY"])

(defn assets
  "Repeat each unique asset `n` times (default 5)."
  ([] (assets 5))
  ([n] (vec (mapcat #(repeat n %) unique-assets))))

(def timeout-ms 5000)

(defn- timed-snapshot
  "Missionary task that times a single quote-snapshot."
  [qm asset]
  (m/sp
   (let [t0 (System/nanoTime)
         q (m/? (quote/quote-snapshot qm timeout-ms asset))
         elapsed-ms (/ (double (- (System/nanoTime) t0)) 1e6)]
     {:asset asset
      :time-ms (if q
                 (long (Math/round elapsed-ms))
                 "timeout")})))

(defn run
  "Time quote-snapshots for assets, print a table, then exit.
  Pass `:parallel true` to run all snapshots concurrently via m/join.
  Pass `:repeat n` to repeat each unique asset n times (default 5)."
  [{:keys [running-system parallel repeat] :or {parallel false repeat 5}}]
  (let [qm (:quote-manager running-system)
        asset-list (assets repeat)
        tasks (map #(timed-snapshot qm %) asset-list)
        _ (println (format "Running %s quote-snapshots (%d assets, repeat %d)..."
                           (if parallel "concurrently" "sequentially")
                           (count asset-list)
                           repeat))
        _ (m/? (m/sleep 5000)) ; make sure all quote accounts are connected.
        rows (if parallel
               (m/? (apply m/join vector tasks))
               (mapv #(m/? %) tasks))]
    (print-table [:asset :time-ms] rows)
    (m/? (m/sleep 5000)) ; wait for loggers to flush
    (m/? (m/sleep 35000)) ; wait for heartbeats to be needed
    ))
