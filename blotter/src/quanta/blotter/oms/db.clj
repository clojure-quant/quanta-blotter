(ns quanta.blotter.oms.db
  (:require
   [clojure.edn :as edn]
   [tick.core :as t]
   [datahike.api :as d]
   [crockery.core :as crockery]
   [quanta.util.datahike :as datahike]))

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
   {:db/ident :order/account-db
    :db/valueType :db.type/ref
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
   {:db/ident :order/limit
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/date
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/text
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/campaign
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :order/label
    :db/valueType :db.type/keyword
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
   {:db/ident :fill/account-db
    :db/valueType :db.type/ref
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
   {:db/ident :fill/campaign
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :fill/label
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   ;; position (created once, then updated)
   {:db/ident :position/account
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :position/account-db
    :db/valueType :db.type/ref
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
    :db/cardinality :db.cardinality/one}
   ;; account (created once, then updated)
   {:db/ident :account/id
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :account/trader
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :account/api
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :account/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :account/notes
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :account/enabled
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident :account/balance
    :db/valueType :db.type/bigdec
    :db/cardinality :db.cardinality/one}
   {:db/ident :account/settings
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])




;; ---------------------------------------------------------------------------
;; accounts

(defn- next-account-id [conn]
  (inc (or (ffirst (d/q '[:find (max ?id) :where [_ :account/id ?id]] @conn)) 0)))

(defn- parse-settings [account]
  (update account :account/settings #(when % (edn/read-string %))))

(defn account-by-id [conn id]
  (ffirst (d/q '[:find (pull ?e [*]) :in $ ?id :where [?e :account/id ?id]] @conn id)))

(defn create-account
  [conn account-map]
  (let [id (or (:account/id account-map) (next-account-id conn))
        entity {:account/id id
                :account/trader (:account/trader account-map)
                :account/api (:account/api account-map)
                :account/enabled false
                :account/balance 0M
                :account/name "new-account"}]
    (d/transact conn [entity])
    id))

(defn enable-account
  [conn account-id enabled]
  (when-let [account (account-by-id conn account-id)]
    (d/transact conn [{:db/id (:db/id account) :account/enabled enabled}])))

(defn update-account
  [conn account-map]
  (when-let [account (account-by-id conn (:account/id account-map))]
    (let [updates (cond-> {:db/id (:db/id account)}
                    (:account/notes account-map) (assoc :account/notes (:account/notes account-map))
                    (:account/name account-map) (assoc :account/name (:account/name account-map))
                    (:account/settings account-map) (assoc :account/settings (pr-str (:account/settings account-map))))]
      (when (> (count updates) 1)
        (d/transact conn [updates])))))

(defn- query-accounts [conn query & args]
  (->> (apply d/q query @conn args)
       (map parse-settings)))

(defn all-enabled-accounts [conn]
  (query-accounts conn
                  '[:find [(pull ?e [*]) ...]
                    :where [?e :account/enabled true]]))

(defn trader-account-list [conn trader]
  (query-accounts conn
                  '[:find [(pull ?e [*]) ...]
                    :in $ ?trader
                    :where [?e :account/trader ?trader]]
                  trader))

;; ---------------------------------------------------------------------------
;; transactor state
;;
;; We never generate ids ourselves. New orders / positions are transacted and
;; the datahike-assigned :db/id is read back from the tx-report and remembered
;; in an in-memory map so that later updates can target the same entity.

(defn new-state []
  (atom {:order-id->eid {}
         :pos-key->eid {}
         :seen-fills #{}
         :account-id->eid {}}))

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

(defn- account-eid
  [conn snapshot account-id]
  (when account-id
    (or (get-in snapshot [:account-id->eid account-id])
        (some-> (account-by-id conn account-id) :db/id))))

(defn order->entity [eid order account-ref]
  (cond-> {:db/id eid
           :order/id (as-str (:order/id order))}
    account-ref (assoc :order/account-db account-ref)
    (:order/account-id order) (assoc :order/account-id (:order/account-id order))
    (:order/asset order) (assoc :order/asset (:order/asset order))
    (:order/side order) (assoc :order/side (:order/side order))
    (:order/type order) (assoc :order/type (:order/type order))
    (:order/status order) (assoc :order/status (:order/status order))
    (some? (:order/qty order)) (assoc :order/qty (as-bigdec (:order/qty order)))
    (some? (:order/qty-filled order)) (assoc :order/qty-filled (as-bigdec (:order/qty-filled order)))
    (some? (:order/qty-working order)) (assoc :order/qty-working (as-bigdec (:order/qty-working order)))
    (some? (:order/avg-price order)) (assoc :order/avg-price (as-bigdec (:order/avg-price order)))
    (some? (:order/limit order)) (assoc :order/limit (as-bigdec (:order/limit order)))
    (:order/date order) (assoc :order/date (as-date (:order/date order)))
    (:order/text order) (assoc :order/text (:order/text order))
    (:order/campaign order) (assoc :order/campaign (:order/campaign order))
    (:order/label order) (assoc :order/label (:order/label order))
    (:order/history order) (assoc :order/history (pr-str (:order/history order)))))

(defn fill->entity [eid order-ref fill account-ref]
  (cond-> {:db/id eid
           :fill/id (as-str (:fill/id fill))
           :fill/order-id (as-str (:fill/order-id fill))
           :fill/account-id (:fill/account-id fill)
           :fill/side (:fill/side fill)}
    account-ref (assoc :fill/account-db account-ref)
    order-ref (assoc :fill/order order-ref)
    (:fill/asset fill) (assoc :fill/asset (:fill/asset fill))
    (some? (:fill/label fill)) (assoc :fill/label (:fill/label fill))
    (some? (:fill/campaign fill)) (assoc :fill/campaign (:fill/campaign fill))
    (some? (:fill/qty fill)) (assoc :fill/qty (as-bigdec (:fill/qty fill)))
    (some? (:fill/price fill)) (assoc :fill/price (as-bigdec (:fill/price fill)))
    (:fill/date fill) (assoc :fill/date (as-date (:fill/date fill)))))

(defn position->entity [eid position account-ref]
  (cond-> {:db/id eid
           :position/account (:position/account position)
           :position/asset (:position/asset position)
           :position/side (:position/side position)
           :position/open (:position/open position)
           :position/realized-pl (as-bigdec (or (:position/realized-pl position) 0M))}
    account-ref (assoc :position/account-db account-ref)
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
  "Builds {:tx tx-data :block-tempids m :account-id->eid m} for a block.
   block is a flat vector like [:msg m :order o :fill f :position p ...].

   Orders/positions appearing multiple times in one block are merged into a
   single entity (last value per attribute wins). Orders are emitted before
   fills so that a fill can reference its order's (temp) id."
  [conn snapshot block]
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
        account-refs (atom {})
        resolve-account! (fn [account-id]
                           (when account-id
                             (when-not (contains? @account-refs account-id)
                               (when-let [eid (account-eid conn snapshot account-id)]
                                 (swap! account-refs assoc account-id eid)))
                             (get @account-refs account-id)))
        order-tx (mapv (fn [[oid o]]
                         (order->entity (order-eid snapshot block-tempids oid) o
                                        (resolve-account! (:order/account-id o))))
                       orders)
        position-tx (mapv (fn [[k p]]
                            (position->entity (pos-eid snapshot block-tempids k) p
                                              (resolve-account! (:position/account p))))
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
                         (conj tx (fill->entity eid order-ref f
                                                (resolve-account! (:fill/account-id f))))))))
                 []
                 fills)]
    {:tx (vec (concat order-tx position-tx msg-tx fill-tx))
     :block-tempids @block-tempids
     :account-id->eid @account-refs}))

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
        {:keys [tx block-tempids account-id->eid]} (build-tx conn snapshot block)]
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
                  (update s :account-id->eid merge account-id->eid)
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
