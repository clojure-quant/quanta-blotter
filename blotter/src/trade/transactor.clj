(ns quanta.market.trade.transactor
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [missionary.core :as m]
   [quanta.market.util :refer [flow-sender start-logging mix]]
   [quanta.market.trade.schema :as s]
   [quanta.market.trade.print :as print]
   [quanta.market.trade.order :refer [order-change-flow
                                      working-orders-flow
                                      order-dict->order-seq-flow
                                      trade-flow]]
   [quanta.market.trade.position :refer [position-change-flow
                                         open-positions-flow]]))

(defn position-dict->positions [position-dict]
  (map (fn [[[account asset] net-qty]]
         {:account account
          :asset asset
          :net-qty net-qty}) position-dict))

(defn get-alert [order-change]
  (get-in order-change [:transactions :alert]))

(defn transactor-alert [order-change-f]
  (m/eduction
   (filter get-alert)
   (map get-alert)
   order-change-f))

(defn snapshot [working-orders open-positions]
  (warn "snapshot: " working-orders open-positions)
  {:working-orders working-orders
   :open-positions open-positions})

(defn transactor-start
  "starts the transactor.
   the transactor keeps working-orders and open-positions from
   a order-orderupdate-flow.

   db is optional. if no db is passed, the database will not get updated and you 
   are working purely in memory."
  [{:keys [order-orderupdate-flow logfile db]}]
  ; load-working-orders from db.
  (let [; flows
        order-change-f (order-change-flow order-orderupdate-flow)
        working-order-dict-f (working-orders-flow order-change-f)
        working-order-f (order-dict->order-seq-flow working-order-dict-f)
        trade-f (trade-flow order-change-f)
        position-change-f (position-change-flow trade-f)
        open-position-f (open-positions-flow position-change-f)
        alert-f (transactor-alert order-change-f)
        snapshot-a (atom {})
        wo-cont-f (m/reductions (fn [r v] v) nil working-order-f)
        op-cont-f (m/reductions (fn [r v] v) nil open-position-f)
        update-snapshot-t (m/reduce (fn [_s v]
                                      (warn "updater received: " v)
                                      (reset! snapshot-a v))
                                    {}
                                    (m/latest snapshot
                                              (m/relieve {} wo-cont-f)
                                              (m/relieve {} op-cont-f)))
        snapshot-dispose! (update-snapshot-t #(prn ::snapshot-success %)
                                             #(prn ::snapshot-crash %))
        state {:db db
              ; :working-orders working-orders
               :order-orderupdate-flow order-orderupdate-flow
               :open-position-f open-position-f
               :working-order-f working-order-f
               :trade-f trade-f
               :alert-f alert-f
               :snapshot-dispose! snapshot-dispose!
               :snapshot-a snapshot-a}]
    state))

(defn transactor-stop [{:keys [snapshot-dispose!]}]
  (info "transactor stopping..")
  (when snapshot-dispose!
    (snapshot-dispose!)))

;; transactor log

(defn wrap-title [title flow]
  (m/eduction (map (fn [data]
                     (str "\r\n" title "\r\n"
                          (if (string? data)
                            data
                            (pr-str data))))) flow))

(defn wrap-table [flow]
  (m/eduction
   (map print/working-orders-table)
   flow))

(defn wrap-positions [flow]
  (m/eduction
   (map position-dict->positions)
   (map print/open-positions-table)
   flow))

(defn transactor-log-start!
  "writes transactor updates to a logfile"
  [transactor logfile]
  (let [{:keys [order-change-f working-order-f
                trade-f
                position-change-f open-position-f]} transactor
        working-order-table-f (wrap-table working-order-f)
        open-position-table-f (wrap-positions open-position-f)
        log-flow (mix ; order
                  (wrap-title "order-change" order-change-f)
                  (wrap-title "working-orders" working-order-f)
                  (wrap-title "working-orders-table" working-order-table-f)
                  ; trade
                  (wrap-title "trade" trade-f) ;
                  ; position
                  (wrap-title "position-change" position-change-f)
                  (wrap-title "open-positions" open-position-f)
                  (wrap-title "open-positions-table" open-position-table-f))]
    (info "transactor is logging to: " logfile)
    (start-logging logfile log-flow)))

(defn transactor-log-stop! [logger-dispose!]
  (when logger-dispose!
    (logger-dispose!)))

(comment
  (position-dict->positions {[:rene/test4 "BTCUSDT.S"] 0.002})
 ; 
  )



