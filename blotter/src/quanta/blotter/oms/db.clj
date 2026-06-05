(ns quanta.blotter.oms.db
  (:require
   [taoensso.timbre :as timbre :refer [info warn]]
   [tick.core :as t]
   [datahike.api :as d]
   [crockery.core :as crockery]))

(def schema
  [;; message (append only)
   {:db/ident :message/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :message/date
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :message/account-id
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :message/asset
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :message/data
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   ;; order (created once, then updated)
   {:db/ident :order/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/account-id
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/asset
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/side
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/qty
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/qty-filled
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/qty-working
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/avg-price
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/date
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/text
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/history
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   ;; fill (stored once)
   {:db/ident :fill/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :fill/order-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :fill/order
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :fill/account-id
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :fill/asset
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :fill/side
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :fill/qty
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :fill/price
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :fill/date
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   ;; position (created once, then updated)
   {:db/ident :position/account
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/asset
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/side
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/qty
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/qty-open
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/open
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/average-entry-price
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/avg-exit-price
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/realized-pl
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/date-open
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/date-close
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn- path->id
  "Deterministic store id derived from the path, so connect/create agree for a
   given path while different paths get distinct ids. (datahike requires a store :id)"
  [path]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str path) "UTF-8")))

(defn- file-cfg [path]
  {:store {:backend :file ; backends: in-memory, file-based, LevelDB, PostgreSQL
           :path path
           :id (path->id path)}
   :keep-history? false
   :schema-flexibility :write  ;default - strict value types need to be defined in advance. 
   ;:schema-flexibility :read ; transact any  kind of data into the database you can set :schema-flexibility to read
   :initial-tx schema ; commit a schema
   })


(defn- mem-cfg [id]
  {:store {:backend :memory
           :id id}
   :keep-history? false
   :schema-flexibility :write
   :initial-tx schema})

(defn- create! [cfg]
  (warn "creating datahike db..")
  (when (d/database-exists? cfg)
    (d/delete-database cfg))
  (d/create-database cfg)
  (d/connect cfg))

(defn trade-db-start [db-path]
  (let [cfg (file-cfg db-path)]
    (info "trade-db starting at path: " db-path)
    (if (d/database-exists? cfg)
      (d/connect cfg)
      (create! cfg))))

(defn trade-db-start-mem
  "Starts an in-memory datahike db. Useful for tests / repl.
   The optional id must be a UUID; a random one is generated otherwise."
  ([] (trade-db-start-mem (java.util.UUID/randomUUID)))
  ([id]
   (let [id (if (uuid? id) id (java.util.UUID/randomUUID))]
     (info "trade-db (mem) starting with id: " id)
     (create! (mem-cfg id)))))

(defn trade-db-stop [conn]
  (when conn
    (info "trade-db stopping ..")
    (d/release conn)))

;; ---------------------------------------------------------------------------
;; transactor state
;;
;; We never generate ids ourselves. New orders / positions are transacted and
;; the datahike-assigned :db/id is read back from the tx-report and remembered
;; in an in-memory map so that later updates can target the same entity.

(defn new-state []
  (atom {:order-id->eid {}
         :pos-key->eid {}
         :seen-fills #{}}))

(defn- as-str [v]
  (when (some? v) (str v)))

(defn- as-bigdec [v]
  (when (some? v)
    (if (decimal? v) v (bigdec v))))

(defn- as-date
  "Datahike :db.type/instant requires a java.util.Date. tick / the #time/instant
   reader produce java.time.Instant, so coerce to a Date (millisecond precision)."
  [v]
  (when (some? v) (t/inst v)))

;; ---------------------------------------------------------------------------
;; entity builders -> datahike entity maps (with :db/id tempid or known eid)

(defn message->entity [eid msg]
  (cond-> {:db/id eid
           :message/type (:type msg)
           :message/date (or (as-date (:date msg)) (t/inst))
           :message/account-id (:account/id msg)
           :message/data (pr-str msg)}
    (:asset msg) (assoc :message/asset (:asset msg))))

(defn order->entity [eid order]
  (cond-> {:db/id eid
           :order/id (as-str (:order/id order))}
    (:order/account-id order) (assoc :order/account-id (:order/account-id order))
    (:order/asset order) (assoc :order/asset (:order/asset order))
    (:order/side order) (assoc :order/side (:order/side order))
    (:order/type order) (assoc :order/type (:order/type order))
    (:order/status order) (assoc :order/status (:order/status order))
    (some? (:order/qty order)) (assoc :order/qty (as-bigdec (:order/qty order)))
    (some? (:order/qty-filled order)) (assoc :order/qty-filled (as-bigdec (:order/qty-filled order)))
    (some? (:order/qty-working order)) (assoc :order/qty-working (as-bigdec (:order/qty-working order)))
    (some? (:order/avg-price order)) (assoc :order/avg-price (as-bigdec (:order/avg-price order)))
    (:order/date order) (assoc :order/date (as-date (:order/date order)))
    (:order/text order) (assoc :order/text (:order/text order))
    (:order/history order) (assoc :order/history (pr-str (:order/history order)))))

(defn fill->entity [eid order-ref fill]
  (cond-> {:db/id eid
           :fill/id (as-str (:fill/id fill))
           :fill/order-id (as-str (:fill/order-id fill))
           :fill/account-id (:fill/account-id fill)
           :fill/side (:fill/side fill)}
    order-ref (assoc :fill/order order-ref)
    (:fill/asset fill) (assoc :fill/asset (:fill/asset fill))
    (some? (:fill/qty fill)) (assoc :fill/qty (as-bigdec (:fill/qty fill)))
    (some? (:fill/price fill)) (assoc :fill/price (as-bigdec (:fill/price fill)))
    (:fill/date fill) (assoc :fill/date (as-date (:fill/date fill)))))

(defn position->entity [eid position]
  (cond-> {:db/id eid
           :position/account (:position/account position)
           :position/asset (:position/asset position)
           :position/side (:position/side position)
           :position/open (:position/open position)
           :position/realized-pl (as-bigdec (or (:position/realized-pl position) 0M))}
    (some? (:position/qty position)) (assoc :position/qty (as-bigdec (:position/qty position)))
    (some? (:position/qty-open position)) (assoc :position/qty-open (as-bigdec (:position/qty-open position)))
    (some? (:position/average-entry-price position))
    (assoc :position/average-entry-price (as-bigdec (:position/average-entry-price position)))
    (some? (:position/avg-exit-price position))
    (assoc :position/avg-exit-price (as-bigdec (:position/avg-exit-price position)))
    (:position/date-open position) (assoc :position/date-open (as-date (:position/date-open position)))
    (:position/date-close position) (assoc :position/date-close (as-date (:position/date-close position)))))

;; ---------------------------------------------------------------------------
;; process a block

(defn- order-eid
  "Resolves the eid/tempid to use for an order in this block.
   Reuses a known eid (update), an in-block tempid (already seen this block),
   or allocates a fresh negative tempid for a brand new order."
  [state block-tempids order-id-str]
  (or (get (:order-id->eid state) order-id-str)
      (get @block-tempids [:order order-id-str])
      (let [tmp (- (- (count @block-tempids)) 1)]
        (swap! block-tempids assoc [:order order-id-str] tmp)
        tmp)))

(defn- pos-eid
  [state block-tempids pos-key]
  (or (get (:pos-key->eid state) pos-key)
      (get @block-tempids [:position pos-key])
      (let [tmp (- (- (count @block-tempids)) 1)]
        (swap! block-tempids assoc [:position pos-key] tmp)
        tmp)))

(defn- msg-eid [block-tempids]
  (let [n (count @block-tempids)
        tmp (- (- n) 1)]
    (swap! block-tempids assoc [:msg n] tmp)
    tmp))

(defn- fill-eid [block-tempids fill-id-str]
  (let [tmp (- (- (count @block-tempids)) 1)]
    (swap! block-tempids assoc [:fill fill-id-str] tmp)
    tmp))

(defn build-tx
  "Builds {:tx tx-data :block-tempids m} for a block.
   block is a flat vector like [:msg m :order o :fill f :position p ...].

   Orders/positions appearing multiple times in one block are merged into a
   single entity (last value per attribute wins). Orders are emitted before
   fills so that a fill can reference its order's (temp) id."
  [snapshot block]
  (let [pairs (partition 2 block)
        of-kind (fn [k] (->> pairs (filter #(= k (first %))) (map second)))
        ;; merge orders by order-id preserving insertion order
        orders (reduce (fn [m o] (update m (as-str (:order/id o)) merge o))
                       (array-map) (of-kind :order))
        positions (reduce (fn [m p]
                            (update m [(:position/account p) (:position/asset p)] merge p))
                          (array-map) (of-kind :position))
        msgs (of-kind :msg)
        fills (of-kind :fill)
        block-tempids (atom {})
        order-tx (mapv (fn [[oid o]]
                         (order->entity (order-eid snapshot block-tempids oid) o))
                       orders)
        position-tx (mapv (fn [[k p]]
                            (position->entity (pos-eid snapshot block-tempids k) p))
                          positions)
        msg-tx (mapv (fn [m] (message->entity (msg-eid block-tempids) m)) msgs)
        fill-tx (reduce
                 (fn [tx f]
                   (let [fid (as-str (:fill/id f))]
                     (if (or (contains? (:seen-fills snapshot) fid)
                             (contains? @block-tempids [:fill fid]))
                       tx ; fills stored only once
                       (let [oid (as-str (:fill/order-id f))
                             order-ref (or (get (:order-id->eid snapshot) oid)
                                           (get @block-tempids [:order oid]))
                             eid (fill-eid block-tempids fid)]
                         (conj tx (fill->entity eid order-ref f))))))
                 []
                 fills)]
    {:tx (vec (concat order-tx position-tx msg-tx fill-tx))
     :block-tempids @block-tempids}))

(defn- resolve-eid
  "Maps a (possibly tempid) eid to the resolved entity id using the tx-report tempids."
  [tempids eid]
  (if (and (number? eid) (neg? eid))
    (get tempids eid eid)
    eid))

(defn process
  "Persists a block to datahike.
   - conn:  datahike connection
   - state: atom created with (new-state)
   - block: a flat vector [:msg m :order o :fill f :position p ...].
   Batches the whole block into a single transaction, then reads the
   datahike-assigned :db/id from the tx-report and remembers it so future
   updates target the same order/position entity."
  [conn state block]
  (let [snapshot @state
        {:keys [tx block-tempids]} (build-tx snapshot block)]
    (if (empty? tx)
      nil
      (let [report (d/transact conn tx)
            tempids (:tempids report)]
        (swap! state
               (fn [s]
                 (reduce
                  (fn [s [[kind k] tmp]]
                    (let [eid (resolve-eid tempids tmp)]
                      (case kind
                        :order (assoc-in s [:order-id->eid k] eid)
                        :position (assoc-in s [:pos-key->eid k] eid)
                        :fill (update s :seen-fills conj k)
                        s)))
                  s
                  block-tempids)))
        report))))

;; ---------------------------------------------------------------------------
;; queries

(defn query-messages [conn]
  (->> (d/q '[:find [(pull ?e [:message/type
                               :message/date
                               :message/account-id
                               :message/asset
                               :message/data]) ...]
              :where [?e :message/type _]]
            @conn)
       (sort-by :message/date)))

(defn query-orders [conn]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :order/id _]]
       @conn))

(defn query-fills [conn]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :fill/id _]]
       @conn))

(defn query-positions [conn]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :position/account _]]
       @conn))

(defn print-orders [conn]
  (crockery/print-table (query-orders conn)))

(defn print-positions [conn]
  (crockery/print-table (query-positions conn)))
