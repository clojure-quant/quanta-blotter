(ns quanta.blotter.oms.portfolio.open-position
  (:require
   [quanta.blotter.precision :as precision]
   [tick.core :as t])
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
         scale (reduce max 0 (keep (fn [x]
                                     (when (some? x)
                                       (.scale ^BigDecimal (bigdec x))))
                                   [entry pl max-qty]))]
     (when (and max-qty (pos? max-qty) entry)
       (case side
         :long (+ entry (precision/div pl max-qty scale))
         :short (- entry (precision/div pl max-qty scale))
         nil)))))

(defn position-key
  "Key for a fill or position view: [account asset]."
  [x]
  (or (when (and (:fill/account-id x) (:fill/asset x))
        [(:fill/account-id x) (:fill/asset x)])
      (when (and (:position/account x) (:position/asset x))
        [(:position/account x) (:position/asset x)])))

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

(defn- net-qty
  "Signed open exposure from DB-shaped fields."
  [position]
  (let [q (or (:position/qty-open position) 0M)]
    (case (:position/side position)
      :long q
      :short (- q)
      0M)))

(defn- set-net
  "Write signed net back onto :position/side + :position/qty-open."
  [position net]
  (cond
    (zero? net)
    (assoc position
           :position/open false
           :position/qty-open 0M)

    (pos? net)
    (assoc position
           :position/open true
           :position/side :long
           :position/qty-open net)

    :else
    (assoc position
           :position/open true
           :position/side :short
           :position/qty-open (num-abs net))))

(defn- current-avg-entry [position]
  (let [net (net-qty position)
        lots (:lots position)
        scale (or (:price-scale position) 0)]
    (cond
      (zero? net) nil
      (seq lots) (lots->avg-entry lots scale)
      :else (:position/average-entry-price position))))

(defn to-position-view
  "Refresh derived fields on a DB-shaped position map."
  [position]
  (let [open? (true? (:position/open position))
        entry (or (current-avg-entry position)
                  (:position/average-entry-price position))
        view (cond-> (assoc position
                            :position/average-entry-price entry
                            :position/realized-pl (or (:position/realized-pl position) 0M)
                            :position/date-open (some-> (:position/date-open position) t/inst))
               (not open?) (assoc :position/date-close
                                  (some-> (:position/date-close position) t/inst))
               open? (dissoc :position/date-close))]
    (assoc view :position/avg-exit-price (derive-avg-exit-price view))))

(defn- public-view [position]
  (-> (to-position-view position)
      (dissoc :lots :price-scale)))

(defn- empty-position [fill]
  {:position/account (:fill/account-id fill)
   :position/asset (:fill/asset fill)
   :position/side nil
   :position/open false
   :position/qty-open 0M
   :position/qty 0M
   :position/average-entry-price nil
   :position/realized-pl 0M
   :position/date-open nil
   :position/date-close nil
   :lots []
   :price-scale 0})

(defn- stamp-ids [position fill]
  (-> position
      (assoc :position/account (or (:position/account position) (:fill/account-id fill))
             :position/asset (or (:position/asset position) (:fill/asset fill))
             :price-scale (max (or (:price-scale position) 0)
                               (if-let [p (:fill/price fill)]
                                 (.scale ^BigDecimal p)
                                 0)))))

(defn- finalize-after-fill [position fill prev-net]
  (let [net (net-qty position)
        abs-net (num-abs net)
        event-date (or (some-> (:fill/date fill) t/inst) (t/inst))
        avg (current-avg-entry position)
        max-q (max (or (:position/qty position) 0M) abs-net)]
    (cond-> (assoc position :position/qty max-q)
      (and (zero? prev-net) (not (zero? net)))
      (assoc :position/date-open event-date
             :position/date-close nil)

      (and (zero? net) (not (zero? prev-net)))
      (assoc :position/date-close event-date)

      (not (zero? net))
      (assoc :position/average-entry-price avg)

      (zero? net)
      (assoc :position/average-entry-price
             (or (:position/average-entry-price position) avg)))))

(defn- apply-fill-average
  [position fill]
  (let [trade (signed-trade-qty fill)
        net (net-qty position)
        avg (or (:position/average-entry-price position) 0M)
        realized (or (:position/realized-pl position) 0M)
        scale (or (:price-scale position) 0)
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
        (-> position
            (set-net new-net)
            (assoc :position/average-entry-price new-avg
                   :position/realized-pl realized
                   :lots [])))

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
          (-> position
              (set-net 0M)
              (assoc :position/average-entry-price avg
                     :position/realized-pl new-realized
                     :lots []))

          (not (same-direction? net remainder-net))
          (-> position
              (set-net remainder-net)
              (assoc :position/average-entry-price price
                     :position/realized-pl new-realized
                     :lots []))

          :else
          (-> position
              (set-net remainder-net)
              (assoc :position/average-entry-price avg
                     :position/realized-pl new-realized
                     :lots [])))))))

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
  [position fill]
  (let [trade (signed-trade-qty fill)
        net (net-qty position)
        lots (or (:lots position) [])
        realized (or (:position/realized-pl position) 0M)
        scale (or (:price-scale position) 0)
        price (:fill/price fill)
        trade-qty (num-abs trade)
        new-net (+ net trade)]
    (cond
      (zero? net)
      (-> position
          (set-net new-net)
          (assoc :lots [{:qty trade-qty :price price}]
                 :position/realized-pl realized
                 :position/average-entry-price price))

      (same-direction? net trade)
      (let [lots (conj lots {:qty trade-qty :price price})]
        (-> position
            (set-net new-net)
            (assoc :lots lots
                   :position/realized-pl realized
                   :position/average-entry-price (lots->avg-entry lots scale))))

      :else
      (let [close-qty (min (num-abs net) trade-qty)
            [lots _ pl] (if (pos? net)
                          (fifo-consume-long lots price close-qty)
                          (fifo-consume-short lots price close-qty))
            new-realized (+ realized pl)
            open-qty (- trade-qty close-qty)]
        (cond
          (zero? new-net)
          (-> position
              (set-net 0M)
              (assoc :lots []
                     :position/realized-pl new-realized))

          (pos? open-qty)
          (-> position
              (set-net new-net)
              (assoc :lots [{:qty open-qty :price price}]
                     :position/average-entry-price price
                     :position/realized-pl new-realized))

          :else
          (-> position
              (set-net new-net)
              (assoc :lots lots
                     :position/average-entry-price (lots->avg-entry lots scale)
                     :position/realized-pl new-realized)))))))

(defn process-fill
  [position fill {:keys [method]}]
  (let [position (or position (empty-position fill))
        prev-net (net-qty position)
        position (stamp-ids position fill)
        position (case method
                   :fifo (apply-fill-fifo position fill)
                   :average (apply-fill-average position fill))]
    (finalize-after-fill position fill prev-net)))

(defn step
  "Synchronous per-position step.
   Returns {:position new-position :position-change position-or-nil}."
  [position fill opts]
  (let [prev (or position (empty-position fill))
        position (process-fill position fill opts)
        prev-public (public-view prev)
        next-public (public-view position)
        emit? (not= prev-public next-public)]
    {:position position
     :position-change (when emit? (to-position-view position))}))

(defn update-open-position-dict
  "Apply a position-change to the live open-position dictionary."
  [dict position]
  (let [k [(:position/account position) (:position/asset position)]]
    (if (false? (:position/open position))
      (dissoc dict k)
      (assoc dict k position))))

(defn process-trade
  "Apply a trade to the open-position dictionary and return its sparse outputs."
  ([open-position trade]
   (process-trade open-position trade {:method :fifo}))
  ([open-position trade opts]
   (if trade
     (let [pos-key (position-key trade)
           known-pos (get open-position pos-key)
           {:keys [position position-change]} (step known-pos trade opts)
           open-position (update-open-position-dict open-position position)]
       (cond-> {:open-position open-position
                :position position
                :position-change position-change}
         (and position-change (false? (:position/open position-change)))
         (assoc :position-closed position-change)))
     {})))

(defn hydrate-position
  "Build a DB-shaped open-position entry from a persisted open position row.
   FIFO lot history is approximated as a single lot at average entry."
  [position]
  (let [qty-open (bigdec (or (:position/qty-open position) 0M))
        entry (some-> (:position/average-entry-price position) bigdec)
        realized (bigdec (or (:position/realized-pl position) 0M))
        max-qty (bigdec (or (:position/qty position) qty-open))
        price-scale (if entry (.scale ^BigDecimal entry) 0)
        view (-> position
                 (dissoc :db/id :position/account-db)
                 (assoc :position/qty-open qty-open
                        :position/qty max-qty
                        :position/realized-pl realized
                        :lots (if (and entry (pos? qty-open))
                                [{:qty qty-open :price entry}]
                                [])
                        :price-scale price-scale)
                 (cond-> entry (assoc :position/average-entry-price entry)))]
    (cond-> view
      (nil? (:position/avg-exit-price view))
      (assoc :position/avg-exit-price (derive-avg-exit-price view)))))
