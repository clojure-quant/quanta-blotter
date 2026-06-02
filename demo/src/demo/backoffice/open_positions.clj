(ns demo.backoffice.open-positions
  (:require
   [clojure.pprint :refer [print-table]]
   [ednx.edn :refer [slurp-edn]]
   [ednx.tick.edn :refer [add-tick-edn-handlers!]]
   [missionary.core :as m]
   [quanta.blotter.open-positions :as op]))

(add-tick-edn-handlers!)

(defn load-channel-paper []
  (slurp-edn "data/channel-paper.edn"))

(defn- position->row
  [{:position/keys [account asset side qty average-entry-price realized-pl]}]
  {:account account
   :asset asset
   :side side
   :qty qty
   :avg-entry average-entry-price
   :realized-pl realized-pl})

(def ^:private table-cols
  [:account :asset :side :qty :avg-entry :realized-pl])

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

(def channel-flow (m/seed (load-channel-paper)))

(defn run-demo!
  "Reads channel-paper.edn; after each position change prints all positions.

  Optional opts passed to quanta.blotter.open-positions/position-change-flow,
  e.g. {:method :fifo}."
  [opts]
  (let [position-change-flow (op/position-change-flow channel-flow opts)
        closed-positions (atom [])]
    (m/? (m/reduce
          (fn [positions-by-key position]
            (let [k [(:position/account position) (:position/asset position)]
                  positions-by-key (if (= :closed (:position/side position))
                                     (do (swap! closed-positions conj position)
                                         (dissoc positions-by-key k))
                                     (assoc positions-by-key k position))]
              (print-all-tables! positions-by-key closed-positions)
              positions-by-key))
          {}
          position-change-flow))))


(def dispose! 
  (let [pos-change-f (op/position-change-flow channel-flow {:method :fifo})
        open-pos-list-f (op/open-position-list-flow pos-change-f)
        t (m/reduce
           (fn [_ positions]
             ;(println "positions:" positions)
             (print-positions-table! "open positions" positions)
             nil)
           nil
           open-pos-list-f
           ;pos-change-f
           )]
    (t #(println "success:  " %) #(println "error:  " %))))


(dispose!)


(comment
  (run-demo! {:method :fifo})


 ; 
  )
