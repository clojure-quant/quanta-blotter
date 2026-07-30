(ns demo.backoffice.fill
  "Read and print trades from the datahike trade-db, then derive positions
   via the portfolio reducer."
  (:require
   [datahike.api :as d]
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.portfolio.open-position :as open-position]
   [quanta.blotter.oms.print :as print]
   [quanta.util.datahike :as datahike]
   [tick.core :as t]))

(def default-db-path "trade-db-oms-server")

(defn today-since []
  (t/inst (t/beginning (t/today))))

(def default-account-id 3)
(def default-asset "__TEST")

(defn query-fills
  "Fills on `account-id` / `asset` with :fill/date >= `since`."
  [conn {:keys [since account-id asset]}]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?since ?account ?asset
         :where
         [?e :fill/id _]
         [?e :fill/date ?date]
         [(>= ?date ?since)]
         [?e :fill/account-id ?account]
         [?e :fill/asset ?asset]]
       @conn (t/inst since) account-id asset))

(defn- final-open-positions
  [fills _opts]
  (let [positions (reduce
                   (fn [positions fill]
                     (:open-position
                      (open-position/process-trade positions fill)))
                   {}
                   fills)]
    (vec (vals positions))))

(defn- print-open-positions!
  [fills opts]
  (println
   (print/timestamped-table
    "open positions (portfolio)"
    (print/open-positions-table (or (final-open-positions fills opts) [])))))

(defn print-trades!
  ([]
   (print-trades! {}))
  ([{:keys [db-path since account-id asset position-opts]
     :or {db-path default-db-path
          since (today-since)
          account-id default-account-id
          asset default-asset
          position-opts {}}}]
   (let [conn (datahike/db-start {:schema db/schema :db-path db-path})]
     (try
       (let [trades (->> (query-fills conn {:since since
                                            :account-id account-id
                                            :asset asset})
                         (sort-by :fill/date))]
         (println
          (print/timestamped-table
           (str "trades (db) account=" account-id
                " asset=" asset " since=" since)
           (print/trades-table trades)))
         (print-open-positions! trades position-opts))
       (finally
         (datahike/db-stop conn))))))

(comment
  (print-trades!))
