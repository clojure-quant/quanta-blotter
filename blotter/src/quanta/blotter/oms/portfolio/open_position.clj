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
         scale (reduce max 0 (keep #(.scale ^BigDecimal %)
                                   [entry pl max-qty]))]
     (when (and max-qty (pos? max-qty) entry)
       (case side
         :long (+ entry (precision/div pl max-qty scale))
         :short (- entry (precision/div pl max-qty scale))
         nil)))))

(defn initial-acc []
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

(defn- current-avg-entry [{:keys [net-qty avg-entry-price lots price-scale]}]
  (if (zero? net-qty)
    nil
    (if (seq lots)
      (lots->avg-entry lots (or price-scale 0))
      avg-entry-price)))

(defn to-position-view
  [acc]
  (let [{:keys [account asset net-qty realized-pl max-qty date-open date-close
                last-side last-avg-entry]} acc
        open? (not (zero? net-qty))
        long? (pos? net-qty)
        side (if open? (if long? :long :short) last-side)
        qty-open (if open? (num-abs net-qty) 0M)
        avg (current-avg-entry acc)
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

(defn- view-changed? [acc]
  (not= (to-position-view acc) (:last-view acc)))

(defn- should-emit? [acc]
  (let [view (to-position-view acc)]
    (and (view-changed? acc)
         (not (and (false? (:position/open view))
                   (:closed-emitted acc))))))

(defn- record-emit [acc]
  (let [view (to-position-view acc)]
    (assoc acc
           :last-view view
           :closed-emitted (false? (:position/open view)))))

(defn- stamp-ids [acc fill]
  (assoc acc
         :account (or (:account acc) (:fill/account-id fill))
         :asset (or (:asset acc) (:fill/asset fill))
         :price-scale (max (or (:price-scale acc) 0)
                           (if-let [p (:fill/price fill)]
                             (.scale ^BigDecimal p)
                             0))))

(defn- finalize-after-fill [acc fill prev-net]
  (let [net (or (:net-qty acc) 0M)
        abs-net (num-abs net)
        event-date (or (some-> (:fill/date fill) t/inst) (t/instant))
        avg (current-avg-entry acc)
        max-q (max (or (:max-qty acc) 0M) abs-net)]
    (cond-> (assoc acc :max-qty max-q)
      (and (zero? prev-net) (not (zero? net)))
      (assoc :date-open event-date)

      (and (zero? net) (not (zero? prev-net)))
      (assoc :date-close event-date)

      (not (zero? net))
      (-> (assoc :last-side (if (pos? net) :long :short))
          (assoc :last-avg-entry avg)))))

(defn- apply-fill-average
  [acc fill]
  (let [trade (signed-trade-qty fill)
        net (or (:net-qty acc) 0M)
        avg (or (:avg-entry-price acc) 0M)
        realized (or (:realized-pl acc) 0M)
        scale (or (:price-scale acc) 0)
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
        (assoc acc
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
          (assoc acc
                 :net-qty 0M
                 :avg-entry-price 0M
                 :realized-pl new-realized
                 :closed-emitted false)

          (not (same-direction? net remainder-net))
          (assoc acc
                 :net-qty remainder-net
                 :avg-entry-price price
                 :realized-pl new-realized
                 :closed-emitted false)

          :else
          (assoc acc
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
  [acc fill]
  (let [trade (signed-trade-qty fill)
        net (or (:net-qty acc) 0M)
        lots (or (:lots acc) [])
        realized (or (:realized-pl acc) 0M)
        scale (or (:price-scale acc) 0)
        price (:fill/price fill)
        trade-qty (num-abs trade)
        new-net (+ net trade)]
    (cond
      (zero? net)
      (assoc acc
             :net-qty new-net
             :lots [{:qty trade-qty :price price}]
             :realized-pl realized
             :closed-emitted false)

      (same-direction? net trade)
      (assoc acc
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
          (assoc acc
                 :net-qty 0M
                 :lots []
                 :avg-entry-price 0M
                 :realized-pl new-realized
                 :closed-emitted false)

          (pos? open-qty)
          (assoc acc
                 :net-qty new-net
                 :lots [{:qty open-qty :price price}]
                 :avg-entry-price price
                 :realized-pl new-realized
                 :closed-emitted false)

          :else
          (assoc acc
                 :net-qty new-net
                 :lots lots
                 :avg-entry-price (lots->avg-entry lots scale)
                 :realized-pl new-realized
                 :closed-emitted false))))))

(defn process-fill
  [acc fill {:keys [method]}]
  (let [prev-net (or (:net-qty acc) 0M)
        acc (stamp-ids acc fill)
        acc (case method
              :fifo (apply-fill-fifo acc fill)
              :average (apply-fill-average acc fill))]
    (finalize-after-fill acc fill prev-net)))

(defn step
  "Synchronous per-position step.
   Returns {:acc new-acc :position-change view-or-nil}."
  [acc fill opts]
  (let [acc (process-fill acc fill opts)]
    (if (should-emit? acc)
      (let [acc (record-emit acc)]
        {:acc acc
         :position-change (to-position-view acc)})
      {:acc acc
       :position-change nil})))

(defn update-open-position-dict
  "Apply a position-change view to the live open-position dictionary."
  [dict position]
  (let [k [(:position/account position) (:position/asset position)]]
    (if (false? (:position/open position))
      (dissoc dict k)
      (assoc dict k position))))
