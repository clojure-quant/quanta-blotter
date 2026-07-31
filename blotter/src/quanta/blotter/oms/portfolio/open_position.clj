(ns quanta.blotter.oms.portfolio.open-position
  (:require
   [nano-id.core :refer [nano-id]]
   [quanta.blotter.precision :as precision]
   [tick.core :as t])
  (:import [java.math BigDecimal]))

(defn- decimal-scale [x]
  (if (some? x)
    (.scale ^BigDecimal (bigdec x))
    0))

(defn- average-scale [current-average fill-price]
  (max (decimal-scale current-average)
       (decimal-scale fill-price)))

(defn position-open?
  "True when a position has a positive open quantity."
  [position]
  (pos? (or (:position/qty-open position) 0M)))

(defn- recompute-average
  [qty current-average fill-qty fill-price]
  (let [qty (bigdec (or qty 0M))
        fill-qty (bigdec (or fill-qty 0M))
        current-average (some-> current-average bigdec)
        fill-price (some-> fill-price bigdec)
        new-qty (+ qty fill-qty)
        current-notional (if current-average
                           (* qty current-average)
                           0M)
        new-notional (+ current-notional
                        (* fill-qty (or fill-price 0M)))]
    (when (pos? new-qty)
      (precision/div new-notional
                     new-qty
                     (average-scale current-average fill-price)))))

(defn position-key
  "Return the live dictionary key for a fill or position."
  [x]
  (cond
    (:fill/position-id x)
    (:fill/position-id x)

    (and (:fill/account-id x) (:fill/asset x))
    [(:fill/account-id x) (:fill/asset x)]

    (:position/hedge x)
    (:position/position-id x)

    (and (:position/account x) (:position/asset x))
    [(:position/account x) (:position/asset x)]))

(defn- event-date [fill]
  (or (some-> (:fill/date fill) t/inst)
      (t/inst)))

(defn- fill-side [fill]
  (case (:fill/side fill)
    :buy :long
    :sell :short))

(defn- same-side? [position fill]
  (= (:position/side position) (fill-side fill)))

(defn- realized-change [position close-qty exit-price]
  (let [entry-price (:position/average-entry-price position)]
    (* close-qty
       (case (:position/side position)
         :long (- exit-price entry-price)
         :short (- entry-price exit-price)))))

(defn- open-position [fill qty]
  (let [hedge? (some? (:fill/position-id fill))
        price (bigdec (:fill/price fill))
        qty (bigdec qty)]
    {:position/account (:fill/account-id fill)
     :position/asset (:fill/asset fill)
     :position/side (fill-side fill)
     :position/qty-entry qty
     :position/qty-exit 0M
     :position/qty-open qty
     :position/average-entry-price price
     :position/avg-exit-price nil
     :position/realized-pl 0M
     :position/date-open (event-date fill)
     :position/date-close nil
     :position/position-id (or (:fill/position-id fill)
                               (nano-id 12))
     :position/hedge hedge?}))

(defn- apply-entry [position fill]
  (let [fill-qty (bigdec (:fill/qty fill))
        qty-entry (:position/qty-entry position)
        avg-entry (recompute-average qty-entry
                                     (:position/average-entry-price position)
                                     fill-qty
                                     (:fill/price fill))
        new-qty-entry (+ qty-entry fill-qty)]
    (assoc position
           :position/qty-entry new-qty-entry
           :position/qty-open (- new-qty-entry
                                 (:position/qty-exit position))
           :position/average-entry-price avg-entry)))

(defn- apply-exit [position fill close-qty]
  (let [close-qty (bigdec close-qty)
        qty-exit (:position/qty-exit position)
        new-qty-exit (+ qty-exit close-qty)
        qty-open (- (:position/qty-entry position) new-qty-exit)
        avg-exit (recompute-average qty-exit
                                    (:position/avg-exit-price position)
                                    close-qty
                                    (:fill/price fill))
        realized-pl (+ (:position/realized-pl position)
                       (realized-change position
                                        close-qty
                                        (bigdec (:fill/price fill))))
        closed? (zero? qty-open)]
    (cond-> (assoc position
                   :position/qty-exit new-qty-exit
                   :position/qty-open qty-open
                   :position/avg-exit-price avg-exit
                   :position/realized-pl realized-pl)
      closed? (assoc :position/date-close (event-date fill)))))

(defn- flip-hedge-position
  "Continue a fully-closed hedge under the same position-id on the opposite
   side: prior exits become entries (and vice versa), then apply the overfill
   remainder as entry on the new side."
  [closed fill remainder]
  (let [flipped (assoc closed
                       :position/side (fill-side fill)
                       :position/qty-entry (:position/qty-exit closed)
                       :position/qty-exit (:position/qty-entry closed)
                       :position/average-entry-price (:position/avg-exit-price closed)
                       :position/avg-exit-price (:position/average-entry-price closed)
                       :position/qty-open 0M
                       :position/realized-pl 0M
                       :position/date-close nil)]
    (apply-entry flipped (assoc fill :fill/qty remainder))))

(defn step
  "Apply one fill to a position.
   Returns a vector under :positions-change and, on close, :position-closed."
  [position fill]
  (if-not position
    {:positions-change [(open-position fill (:fill/qty fill))]}
    (if (same-side? position fill)
      {:positions-change [(apply-entry position fill)]}
      (let [fill-qty (bigdec (:fill/qty fill))
            qty-open (:position/qty-open position)
            close-qty (min qty-open fill-qty)
            closed (apply-exit position fill close-qty)
            remainder (- fill-qty close-qty)]
        (if (zero? remainder)
          (cond-> {:positions-change [closed]}
            (not (position-open? closed))
            (assoc :position-closed closed))
          ;; Overfill reverses the position. Hedge keeps the broker position-id
          ;; and swaps entry/exit so prior exits become the new side's entries.
          (let [opened (if (:position/hedge position)
                         (flip-hedge-position closed fill remainder)
                         (open-position (dissoc fill :fill/position-id)
                                        remainder))]
            {:positions-change [closed opened]
             :position-closed closed}))))))

(defn update-open-position-dict
  "Apply public position changes to the live open-position dictionary."
  [dict positions-change]
  (reduce
   (fn [dict position]
     (let [key (position-key position)]
       (if (position-open? position)
         (assoc dict key position)
         (dissoc dict key))))
   dict
   positions-change))

(defn process-trade
  "Apply a trade to the open-position dictionary and return sparse outputs."
  [open-position trade]
  (if trade
    (let [known-position (get open-position (position-key trade))
          {:keys [positions-change] :as result} (step known-position trade)]
      (assoc result
             :open-position
             (update-open-position-dict open-position positions-change)))
    {}))

(defn hydrate-position
  "Normalize a persisted open position without recomputing its averages."
  [position]
  (-> position
      (dissoc :db/id
              :position/account-db)
      (update :position/qty-entry bigdec)
      (update :position/qty-exit bigdec)
      (update :position/qty-open bigdec)
      (update :position/average-entry-price #(some-> % bigdec))
      (update :position/avg-exit-price #(some-> % bigdec))
      (update :position/realized-pl bigdec)
      (update :position/date-open #(some-> % t/inst))
      (update :position/date-close #(some-> % t/inst))))
