(ns quanta.blotter.oms.portfolio.open-position-test
  (:require
   [clojure.test :refer :all]
   [quanta.blotter.oms.portfolio :as portfolio]
   [quanta.blotter.oms.portfolio.open-position :as op]))

(defn- fill [account asset side qty price]
  {:type :broker/order-filled
   :account/id account
   :asset asset
   :side side
   :qty qty
   :price price})

(defn- new-order-for-fill [order-id msg]
  {:type :trader/new-order
   :date (or (:date msg) #inst "2026-06-01T12:00:00.000Z")
   :account/id (:account/id msg)
   :order-id order-id
   :asset (:asset msg)
   :side (:side msg)
   :order-type :market
   :qty (:qty msg)})

(defn- ensure-known-orders [events]
  (:events
   (reduce-kv
    (fn [{:keys [known] :as acc} idx msg]
      (let [order-id (:order-id msg)]
        (cond
          (= :trader/new-order (:type msg))
          (-> acc
              (update :events conj msg)
              (cond-> order-id (update :known conj order-id)))

          (= :broker/order-filled (:type msg))
          (if (contains? known order-id)
            (-> acc
                (update :events conj msg)
                (update :known disj order-id))
            (let [order-id (or order-id (str "position-test-order-" idx))
                  msg (assoc msg :order-id order-id)]
              (update acc :events into [(new-order-for-fill order-id msg) msg])))

          :else
          (update acc :events conj msg))))
    {:events [] :known #{}}
    (vec events))))

(defn- emissions [events & [opts]]
  (let [method (or (:position-method opts) (:method opts) :average)]
    (second
     (reduce
      (fn [[state outs] msg]
        (let [{:keys [state out-msg]} (portfolio/process-message state msg)]
          [state (cond-> outs
                   (:position-change out-msg)
                   (conj (:position-change out-msg)))]))
      [(portfolio/empty-state {:position-method method}) []]
      (ensure-known-orders events)))))

(defn- last-emission [fills & [opts]]
  (last (emissions fills opts)))

(deftest buy-sell-flip-average
  (let [fills [(fill 1 "X" :buy 100.0 10.0)
               (fill 1 "X" :sell 110.0 11.0)]
        ems (emissions fills {:method :average})]
    (is (= 2 (count ems)))
    (is (= :long (:position/side (nth ems 0))))
    (is (true? (:position/open (nth ems 0))))
    (is (== 100.0 (:position/qty-open (nth ems 0))))
    (is (== 100.0 (:position/qty (nth ems 0))))
    (is (== 10.0 (:position/average-entry-price (nth ems 0))))
    (is (== 0.0 (:position/realized-pl (nth ems 0))))
    (is (= :short (:position/side (nth ems 1))))
    (is (true? (:position/open (nth ems 1))))
    (is (== 10.0 (:position/qty-open (nth ems 1))))
    (is (== 100.0 (:position/qty (nth ems 1))))
    (is (== 11.0 (:position/average-entry-price (nth ems 1))))
    (is (== 100.0 (:position/realized-pl (nth ems 1))))))

(deftest buy-sell-flip-fifo-same-lots
  (let [fills [(fill 1 "X" :buy 100.0 10.0)
               (fill 1 "X" :sell 110.0 11.0)]
        ems (emissions fills {:method :fifo})
        last-pos (last ems)]
    (is (== 10.0 (:position/average-entry-price (first ems))))
    (is (= :short (:position/side last-pos)))
    (is (== 11.0 (:position/average-entry-price last-pos)))
    (is (== 100.0 (:position/realized-pl last-pos)))))

(deftest fifo-consumes-oldest-lot-first
  (let [fills [(fill 1 "X" :buy 50.0 10.0)
               (fill 1 "X" :buy 50.0 12.0)
               (fill 1 "X" :sell 60.0 15.0)]
        pos (last-emission fills {:method :fifo})]
    (is (= :long (:position/side pos)))
    (is (== 40.0 (:position/qty-open pos)))
    (is (== 100.0 (:position/qty pos)))
    (is (== 12.0 (:position/average-entry-price pos)))
    (is (== 280.0 (:position/realized-pl pos)))))

(deftest average-partial-close-keeps-avg
  (let [fills [(fill 1 "X" :buy 100.0 10.0)
               (fill 1 "X" :sell 40.0 12.0)]
        pos (last-emission fills {:method :average})]
    (is (= :long (:position/side pos)))
    (is (== 60.0 (:position/qty-open pos)))
    (is (== 100.0 (:position/qty pos)))
    (is (== 10.0 (:position/average-entry-price pos)))
    (is (== 80.0 (:position/realized-pl pos)))))

(deftest short-close-realized-pl
  (let [fills [(fill 1 "X" :sell 100.0 11.0)
               (fill 1 "X" :buy 100.0 10.0)]
        closed (last-emission fills {:method :average})]
    (is (false? (:position/open closed)))
    (is (= :short (:position/side closed)))
    (is (== 11.0 (:position/average-entry-price closed)))
    (is (== 100.0 (:position/realized-pl closed)))
    (is (instance? java.util.Date (:position/date-open closed)))
    (is (instance? java.util.Date (:position/date-close closed)))))

(deftest date-open-when-fill-has-no-date
  (let [pos (last-emission [(fill 1 "X" :buy 10.0 1.0)])]
    (is (instance? java.util.Date (:position/date-open pos)))
    (is (nil? (:position/date-close pos)))))

(deftest date-open-from-fill-date
  (let [msg (assoc (fill 1 "X" :buy 10.0 1.0) :date #inst "2026-06-01T12:00:00.000Z")
        pos (last-emission [msg])]
    (is (= #inst "2026-06-01T12:00:00.000Z" (:position/date-open pos)))))

(deftest closed-emitted-once
  (let [fills [(fill 1 "X" :buy 10.0 1.0)
               (fill 1 "X" :sell 10.0 2.0)]
        ems (emissions fills)]
    (is (= 2 (count ems)))
    (is (false? (:position/open (last ems))))))

(deftest ignores-non-fill-messages
  (let [ems (emissions [{:type :trader/new-order :date #inst "2026-06-01T12:00:00.000Z"
                         :account/id 1 :asset "X" :side :buy :order-type :market :qty 1.0}
                        (fill 1 "X" :buy 1.0 5.0)])]
    (is (= 1 (count ems)))
    (is (= :long (:position/side (first ems))))))

(deftest channel-paper-fills
  (let [ems (emissions
             [{:type :trader/new-order :date #inst "2026-06-01T12:00:00.000Z"
               :account/id 2 :order-id 4 :asset "ETHUSDT" :side :sell :order-type :market :qty 0.001}
              {:type :broker/order-filled :account/id 2 :order-id 4 :asset "ETHUSDT"
               :qty 0.001 :side :sell :price 100.0}
              {:type :broker/order-filled :account/id 2 :order-id 3 :asset "ETHUSDT"
               :qty 0.001 :side :sell :price 101.0}])
        last-pos (last ems)]
    (is (= 2 (count ems)))
    (is (= :short (:position/side (first ems))))
    (is (== 0.001 (:position/qty-open (first ems))))
    (is (= :short (:position/side last-pos)))
    (is (== 0.002 (:position/qty-open last-pos)))
    (is (== 100.5 (:position/average-entry-price last-pos)))))

(deftest derived-avg-exit-matches-formula
  (let [fills [(fill 1 "X" :buy 100.0 10.0)
               (fill 1 "X" :sell 40.0 12.0)]
        pos (last-emission fills {:method :average})
        max-qty (:position/qty pos)
        entry (:position/average-entry-price pos)
        exit (:position/avg-exit-price pos)
        pl (:position/realized-pl pos)]
    (is (== pl (* max-qty (- exit entry))))
    (is (some? (op/derive-avg-exit-price pos)))))

(deftest position-closed-and-dict-cleared
  (let [fills [(fill 1 "X" :buy 10.0 1.0)
               (fill 1 "X" :sell 10.0 2.0)]
        [state outs] (reduce
                      (fn [[st outs] msg]
                        (let [{:keys [state out-msg]} (portfolio/process-message st msg)]
                          [state (conj outs out-msg)]))
                      [(portfolio/empty-state {:position-method :average}) []]
                      (ensure-known-orders fills))
        closed-out (last outs)]
    (is (some? (:position-closed closed-out)))
    (is (false? (:position/open (:position-closed closed-out))))
    (is (empty? (:open-position state)))))

(deftest reopen-after-close-starts-fresh
  (let [fills [(fill 1 "X" :buy 10.0 1.0)
               (fill 1 "X" :sell 10.0 2.0)
               (fill 1 "X" :buy 5.0 3.0)]
        ems (emissions fills {:method :average})
        reopened (last ems)]
    (is (= 3 (count ems)))
    (is (true? (:position/open reopened)))
    (is (== 5.0 (:position/qty-open reopened)))
    (is (== 0.0 (:position/realized-pl reopened)))
    (is (== 5.0 (:position/qty reopened)))))
