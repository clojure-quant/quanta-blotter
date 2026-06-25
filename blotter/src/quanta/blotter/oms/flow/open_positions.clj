(ns quanta.blotter.oms.flow.open-positions
  (:require
   [missionary.core :as m]
   [quanta.blotter.precision :as precision]
   [tick.core :as t]
   [taoensso.timbre :refer [info]])
  (:import [java.math BigDecimal]))

(defn- num-abs [n]
  (cond
    (nil? n) 0M
    (instance? BigDecimal n) (.abs ^BigDecimal n)
    :else (Math/abs (double n))))

(defn derive-avg-exit-price
  "Derives :position/avg-exit-price so max-qty × price-diff equals :position/realized-pl.
   Long:  pl = max-qty × (avg-exit − avg-entry)
   Short: pl = max-qty × (avg-entry − avg-exit)
   :position/qty must be max size."
  ([position]
   (derive-avg-exit-price position nil))
  ([{:position/keys [side qty realized-pl average-entry-price]} fallback-entry]
   (let [max-qty qty
         entry (or average-entry-price fallback-entry)
         pl (or realized-pl 0M)
         scale (reduce max 0 (keep #(.scale ^BigDecimal %)
                                   [entry pl max-qty]))]
     (when (and max-qty (pos? max-qty) entry)
       (case side
         :long (+ entry (precision/div pl max-qty scale))
         :short (- entry (precision/div pl max-qty scale))
         nil)))))

(defn- initial-state []
  {:net-qty 0M
   :avg-entry-price 0M
   :lots []
   :realized-pl 0M
   :price-scale 0
   :max-qty 0M
   :date-open nil
   :date-close nil
   :last-side nil
   :last-avg-entry nil
   :account nil
   :asset nil
   :closed-emitted false
   :last-view nil})

(defn- position-key [fill]
  [(:fill/account-id fill) (:fill/asset fill)])

(defn- signed-trade-qty [fill]
  (let [q (or (:fill/qty fill) 0M)]
    (case (:fill/side fill)
      :buy q
      :sell (- q))))

(defn- same-direction? [net trade]
  (or (zero? net)
      (and (pos? net) (pos? trade))
      (and (neg? net) (neg? trade))))

(defn- long-realized-pl [entry exit close-qty]
  (* (- exit entry) close-qty))

(defn- short-realized-pl [entry exit close-qty]
  (* (- entry exit) close-qty))

(defn- lots->avg-entry [lots scale]
  (if (empty? lots)
    0M
    (precision/div (reduce + 0M (map #(* (:qty %) (:price %)) lots))
                   (reduce + 0M (map :qty lots))
                   scale)))

(defn- current-avg-entry [{:keys [net-qty avg-entry-price lots price-scale]}]
  (if (zero? net-qty)
    nil
    (if (seq lots)
      (lots->avg-entry lots (or price-scale 0))
      avg-entry-price)))

(defn- to-position-view
  [state]
  (let [{:keys [account asset net-qty realized-pl max-qty date-open date-close
                last-side last-avg-entry]} state
        open? (not (zero? net-qty))
        long? (pos? net-qty)
        side (if open? (if long? :long :short) last-side)
        qty-open (if open? (num-abs net-qty) 0M)
        avg (current-avg-entry state)
        entry (or avg last-avg-entry)
        view (cond-> {:position/account account
                      :position/asset asset
                      :position/side side
                      :position/open open?
                      :position/qty-open qty-open
                      :position/qty max-qty
                      :position/average-entry-price entry
                      :position/realized-pl (or realized-pl 0M)
                      :position/date-open (some-> date-open t/inst)}
               (not open?) (assoc :position/date-close (some-> date-close t/inst)))]
    (assoc view :position/avg-exit-price (derive-avg-exit-price view))))

(defn- view-changed? [state]
  (not= (to-position-view state) (:last-view state)))

(defn- should-emit? [state]
  (let [view (to-position-view state)]
    (and (view-changed? state)
         (not (and (false? (:position/open view))
                   (:closed-emitted state))))))

(defn- record-emit [state]
  (let [view (to-position-view state)]
    (assoc state
           :last-view view
           :closed-emitted (false? (:position/open view)))))

(defn- stamp-ids [state fill]
  (assoc state
         :account (or (:account state) (:fill/account-id fill))
         :asset (or (:asset state) (:fill/asset fill))
         :price-scale (max (or (:price-scale state) 0)
                           (if-let [p (:fill/price fill)]
                             (.scale ^BigDecimal p)
                             0))))

(defn- finalize-after-fill [state fill prev-net]
  (let [net (or (:net-qty state) 0M)
        abs-net (num-abs net)
        event-date (or (some-> (:fill/date fill) t/inst) (t/instant))
        avg (current-avg-entry state)
        max-q (max (or (:max-qty state) 0M) abs-net)]
    (cond-> (assoc state :max-qty max-q)
      (and (zero? prev-net) (not (zero? net)))
      (assoc :date-open event-date)

      (and (zero? net) (not (zero? prev-net)))
      (assoc :date-close event-date)

      (not (zero? net))
      (-> (assoc :last-side (if (pos? net) :long :short))
          (assoc :last-avg-entry avg)))))

(defn- apply-fill-average
  [state fill]
  (let [trade (signed-trade-qty fill)
        net (or (:net-qty state) 0M)
        avg (or (:avg-entry-price state) 0M)
        realized (or (:realized-pl state) 0M)
        scale (or (:price-scale state) 0)
        price (:fill/price fill)
        new-net (+ net trade)]
    (cond
      (same-direction? net trade)
      (let [abs-new (num-abs new-net)
            abs-trade (num-abs trade)
            abs-old (num-abs net)
            new-avg (if (zero? net)
                      price
                      (precision/div (+ (* abs-old avg) (* abs-trade price)) abs-new scale))]
        (assoc state
               :net-qty new-net
               :avg-entry-price new-avg
               :realized-pl realized
               :closed-emitted false))

      :else
      (let [close-qty (min (num-abs net) (num-abs trade))
            long? (pos? net)
            new-realized (+ realized
                            (if long?
                              (long-realized-pl avg price close-qty)
                              (short-realized-pl avg price close-qty)))
            remainder-net new-net]
        (cond
          (zero? remainder-net)
          (assoc state
                 :net-qty 0M
                 :avg-entry-price 0M
                 :realized-pl new-realized
                 :closed-emitted false)

          (not (same-direction? net remainder-net))
          (assoc state
                 :net-qty remainder-net
                 :avg-entry-price price
                 :realized-pl new-realized
                 :closed-emitted false)

          :else
          (assoc state
                 :net-qty remainder-net
                 :avg-entry-price avg
                 :realized-pl new-realized
                 :closed-emitted false))))))

(defn- fifo-consume-long [lots exit-price close-qty]
  (loop [lots lots, rem close-qty, pl 0M]
    (if (or (zero? rem) (empty? lots))
      [lots rem pl]
      (let [{:keys [qty price]} (first lots)
            take-qty (min qty rem)
            rest-qty (- qty take-qty)
            pl (+ pl (long-realized-pl price exit-price take-qty))
            lots (if (pos? rest-qty)
                   (cons {:qty rest-qty :price price} (rest lots))
                   (rest lots))
            rem (- rem take-qty)]
        (recur lots rem pl)))))

(defn- fifo-consume-short [lots exit-price close-qty]
  (loop [lots lots, rem close-qty, pl 0M]
    (if (or (zero? rem) (empty? lots))
      [lots rem pl]
      (let [{:keys [qty price]} (first lots)
            take-qty (min qty rem)
            rest-qty (- qty take-qty)
            pl (+ pl (short-realized-pl price exit-price take-qty))
            lots (if (pos? rest-qty)
                   (cons {:qty rest-qty :price price} (rest lots))
                   (rest lots))
            rem (- rem take-qty)]
        (recur lots rem pl)))))

(defn- apply-fill-fifo
  [state fill]
  (let [trade (signed-trade-qty fill)
        net (or (:net-qty state) 0M)
        lots (or (:lots state) [])
        realized (or (:realized-pl state) 0M)
        scale (or (:price-scale state) 0)
        price (:fill/price fill)
        trade-qty (num-abs trade)
        new-net (+ net trade)]
    (cond
      (zero? net)
      (assoc state
             :net-qty new-net
             :lots [{:qty trade-qty :price price}]
             :realized-pl realized
             :closed-emitted false)

      (same-direction? net trade)
      (assoc state
             :net-qty new-net
             :lots (conj lots {:qty trade-qty :price price})
             :realized-pl realized
             :closed-emitted false)

      :else
      (let [close-qty (min (num-abs net) trade-qty)
            [lots _ pl] (if (pos? net)
                          (fifo-consume-long lots price close-qty)
                          (fifo-consume-short lots price close-qty))
            new-realized (+ realized pl)
            open-qty (- trade-qty close-qty)]
        (cond
          (zero? new-net)
          (assoc state
                 :net-qty 0M
                 :lots []
                 :avg-entry-price 0M
                 :realized-pl new-realized
                 :closed-emitted false)

          (pos? open-qty)
          (assoc state
                 :net-qty new-net
                 :lots [{:qty open-qty :price price}]
                 :avg-entry-price price
                 :realized-pl new-realized
                 :closed-emitted false)

          :else
          (assoc state
                 :net-qty new-net
                 :lots lots
                 :avg-entry-price (lots->avg-entry lots scale)
                 :realized-pl new-realized
                 :closed-emitted false))))))

(defn- process-fill
  [state fill {:keys [method]}]
  (let [prev-net (or (:net-qty state) 0M)
        state (stamp-ids state fill)
        state (case method
                :fifo (apply-fill-fifo state fill)
                :average (apply-fill-average state fill))]
    (finalize-after-fill state fill prev-net)))

(defn- step [[state _] fill opts]
  (let [state (process-fill state fill opts)]
    (if (should-emit? state)
      [(record-emit state) (to-position-view state)]
      [state nil])))

(defn- per-position-view-flow [fill-flow opts]
  (m/eduction
   (map second)
   (filter some?)
   (m/reductions (fn [acc fill] (step acc fill opts))
                 [(initial-state) nil]
                 fill-flow)))

(defn position-change-flow
  "Consumes a flow of fills (see quanta.blotter.oms.flow.fill/fill-flow); emits
   {:position/...} maps when a fill changes the open position for
   [account asset].

   Options:
   - :method — :average (default) or :fifo"
  ([fill-flow]
   (position-change-flow fill-flow {}))
  ([fill-flow {:keys [method] :or {method :average}}]
   (let [opts {:method method}]
     (m/ap
      (let [[k fills] (m/?> ##Inf (m/group-by position-key fill-flow))
            _ (info "open-position flow for" k)
            position (m/?> 1 (per-position-view-flow fills opts))]
        position)))))

(defn open-position-dict-flow [position-change-f]
  (m/reductions
   (fn [acc position]
     (let [k [(:position/account position) (:position/asset position)]
           acc (if (false? (:position/open position))
                 (dissoc acc k)
                 (assoc acc k position))]
       acc))
   {}
   position-change-f))

(defn open-position-list-flow [position-change-f]
  (m/eduction
   (map vals)
   (open-position-dict-flow position-change-f)))

(defn open-position-list-from-dict-flow
  "Emits a vector of open positions from a shared dict flow."
  [dict-flow]
  (m/eduction
   (map vals)
   dict-flow))

(defn closed-position-list-flow [position-change-f]
  (m/eduction
   (remove #(:position/open %))
   position-change-f))