(ns demo.db-print
  "Print orders and positions stored in the datahike trade-db, reusing the
   table formatting from quanta.blotter.oms.print."
  (:require
   [quanta.blotter.oms.db :as db]
   [quanta.blotter.oms.print :as print]
   [quanta.util.datahike :as datahike]))

(def db-path "trade-db")

(defn print-orders! [conn]
  (let [orders (->> (db/query-orders conn)
                    (sort-by :order/id))]
    (println (print/timestamped-table "orders (db)" (print/working-orders-table orders)))))

(defn print-trades! [conn]
  (let [trades (->> (db/query-fills conn)
                    (sort-by :fill/date))]
    (println (print/timestamped-table "trades (db)" (print/trades-table trades)))))

(defn print-positions! [conn]
  (let [positions (->> (db/query-positions conn)
                       (sort-by (juxt :position/account :position/asset)))]
    (println (print/timestamped-table "positions (db)" (print/open-positions-table positions)))))

(defn print-all! [conn]
  (print-orders! conn)
  (print-trades! conn)
  (print-positions! conn))

(comment
  ;; open the trade-db and print what the OMS has persisted
  (def trade-db (datahike/db-start {:schema db/schema :db-path db-path}))

  (print-orders! trade-db)

  (print-trades! trade-db)

  (print-positions! trade-db)
  (print-all! trade-db)

  (datahike/db-stop trade-db)

  ;; or reuse the connection already opened by demo.oms:
  ;; (require 'demo.oms)
  ;; (print-all! demo.oms/trade-db)
  ;
  )
