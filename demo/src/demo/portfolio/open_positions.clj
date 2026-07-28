(ns demo.portfolio.open-positions
  (:require
   [clojure.pprint :refer [print-table]]
   [ednx.edn :refer [slurp-edn]]
   [ednx.tick.edn :refer [add-tick-edn-handlers!]]
   [quanta.blotter.oms.portfolio :as portfolio]))

(add-tick-edn-handlers!)

(defn load-channel-paper []
  (slurp-edn "data/channel-paper.edn"))

(defn- position->row
  [{:position/keys [account asset side open qty-open qty average-entry-price
                    avg-exit-price realized-pl]}]
  {:account account
   :asset asset
   :side side
   :open open
   :qty-open qty-open
   :qty-max qty
   :avg-entry average-entry-price
   :avg-exit avg-exit-price
   :realized-pl realized-pl})

(def ^:private table-cols
  [:account :asset :side :open :qty-open :qty-max :avg-entry :avg-exit :realized-pl])

(defn- position-key [row]
  [(:account row) (:asset row)])

(defn- print-positions-table! [title positions]
  (println title)
  (let [rows (->> positions (map position->row) (sort-by position-key))]
    (if (seq rows)
      (print-table table-cols rows)
      (println "  (none)")))
  (flush))

(defn- print-all-tables! [open-by-key closed-positions]
  (println)
  (print-positions-table! "Open positions:" (vals open-by-key))
  (println)
  (print-positions-table! "Closed positions:" @closed-positions)
  (println))

(defn run-demo!
  "Reads channel-paper.edn; after each position change prints all positions.
  Optional opts e.g. {:position-method :fifo}."
  [opts]
  (let [msgs (load-channel-paper)
        closed-positions (atom [])
        method (or (:position-method opts) (:method opts) :fifo)]
    (reduce
     (fn [state msg]
       (let [{:keys [state out-msg]} (portfolio/process-message state msg)]
         (when-let [p (:position-change out-msg)]
           (when (false? (:position/open p))
             (swap! closed-positions conj p))
           (print-all-tables! (:open-position state) closed-positions))
         state))
     (portfolio/empty-state {:position-method method})
     msgs)
    nil))

(comment
  (run-demo! {:position-method :fifo}))
