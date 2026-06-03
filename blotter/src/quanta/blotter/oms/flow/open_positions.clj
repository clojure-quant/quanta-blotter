(ns quanta.blotter.oms.flow.open-positions
  (:require
   [missionary.core :as m]
   [taoensso.timbre :refer [info]])
  (:import [java.math BigDecimal]))

(defn- num-abs [n]
  (cond
    (nil? n) 0
    (instance? BigDecimal n) (.abs ^BigDecimal n)
    :else (Math/abs (double n))))

(defn- initial-state []
  {:net-qty 0.0
   :avg-entry-price 0.0
   :lots []
   :realized-pl 0.0
   :account nil
   :asset nil
   :closed-emitted false
   :last-view nil})

(defn- position-key [msg]
  [(:account/id msg) (:asset msg)])

(defn- signed-trade-qty [{:keys [side qty]}]
  (let [q (or qty 0.0)]
    (case side
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

(defn- lots->avg-entry [lots]
  (if (empty? lots)
    0.0
    (/ (reduce + 0.0 (map #(* (:qty %) (:price %)) lots))
       (reduce + 0.0 (map :qty lots)))))

(defn- to-position-view
  [{:keys [account asset net-qty avg-entry-price realized-pl lots]}]
  (if (zero? net-qty)
    {:position/account account
     :position/asset asset
     :position/side :closed
     :position/qty 0.0
     :position/average-entry-price nil
     :position/realized-pl (or realized-pl 0.0)}
    (let [long? (pos? net-qty)
          avg (if (seq lots)
                (lots->avg-entry lots)
                avg-entry-price)]
      {:position/account account
       :position/asset asset
       :position/side (if long? :long :short)
       :position/qty (if long? net-qty (- net-qty))
       :position/average-entry-price avg
       :position/realized-pl (or realized-pl 0.0)})))

(defn- view-changed? [state]
  (not= (to-position-view state) (:last-view state)))

(defn- should-emit? [state]
  (let [view (to-position-view state)]
    (and (view-changed? state)
         (not (and (= :closed (:position/side view))
                   (:closed-emitted state))))))

(defn- record-emit [state]
  (let [view (to-position-view state)]
    (assoc state
           :last-view view
           :closed-emitted (= :closed (:position/side view)))))

(defn- stamp-ids [state msg]
  (assoc state
         :account (or (:account state) (:account/id msg))
         :asset (or (:asset state) (:asset msg))))

(defn- apply-fill-average
  [state {:keys [price] :as fill}]
  (let [trade (signed-trade-qty fill)
        net (or (:net-qty state) 0.0)
        avg (or (:avg-entry-price state) 0.0)
        realized (or (:realized-pl state) 0.0)
        new-net (+ net trade)]
    (cond
      (same-direction? net trade)
      (let [abs-new (num-abs new-net)
            abs-trade (num-abs trade)
            abs-old (num-abs net)
            new-avg (if (zero? net)
                      price
                      (/ (+ (* abs-old avg) (* abs-trade price)) abs-new))]
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
                 :net-qty 0.0
                 :avg-entry-price 0.0
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
  (loop [lots lots, rem close-qty, pl 0.0]
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
  (loop [lots lots, rem close-qty, pl 0.0]
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
  [state {:keys [price] :as fill}]
  (let [trade (signed-trade-qty fill)
        net (or (:net-qty state) 0.0)
        lots (or (:lots state) [])
        realized (or (:realized-pl state) 0.0)
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
            [lots rem pl] (if (pos? net)
                            (fifo-consume-long lots price close-qty)
                            (fifo-consume-short lots price close-qty))
            new-realized (+ realized pl)
            open-qty (- trade-qty close-qty)]
        (cond
          (zero? new-net)
          (assoc state
                 :net-qty 0.0
                 :lots []
                 :avg-entry-price 0.0
                 :realized-pl new-realized
                 :closed-emitted false)

          (pos? open-qty)
          (let [flip-long? (neg? new-net)]
            (assoc state
                   :net-qty new-net
                   :lots [{:qty open-qty :price price}]
                   :avg-entry-price price
                   :realized-pl new-realized
                   :closed-emitted false))

          :else
          (assoc state
                 :net-qty new-net
                 :lots lots
                 :avg-entry-price (lots->avg-entry lots)
                 :realized-pl new-realized
                 :closed-emitted false))))))

(defn- process-fill
  [state fill {:keys [method]}]
  (let [state (stamp-ids state fill)]
    (case method
      :fifo (apply-fill-fifo state fill)
      :average (apply-fill-average state fill))))

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
                  acc (if (= :closed (:position/side position))
                        (dissoc acc k)
                        (assoc acc k position))]
              acc))
          {}
          position-change-f))



(defn open-position-list-flow [position-change-f]
  (m/eduction 
   (map vals)
   (open-position-dict-flow position-change-f)
   ))
