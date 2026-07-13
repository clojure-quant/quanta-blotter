(ns quanta.blotter.paper.broker-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [missionary.core :as m]
   [tick.core :as t]
   [quanta.blotter.protocol :as p]
   [quanta.blotter.paper.broker :as broker]
   [quanta.blotter.oms.validation.schema :as s]
   [quanta.quote.core :as qc]))

(def new-order
  {:type :trader/new-order
   :account/id 3
   :order-id 1
   :asset "BTCUSDT"
   :side :buy
   :order-type :limit
   :limit 100.0M
   :qty 0.001M})

(def market-order
  {:type :trader/new-order
   :account/id 3
   :order-id 2
   :asset "BTCUSDT"
   :side :buy
   :order-type :market
   :qty 0.001M})

(defn- test-quotes
  "Finite quote flow at a fixed bid (enough for paper-broker fill tests)."
  [bid]
  (m/seed (repeatedly 20 (fn []
                           {:asset "BTCUSDT"
                            :bid bid
                            :ask (+ bid 0.01M)
                            :ts (t/instant)}))))

(deftest reject-reason-accepts-when-zero
  (is (every? nil? (repeatedly 200 #(broker/reject-reason 0))))
  (is (every? nil? (repeatedly 200 #(broker/reject-reason nil)))
      "missing reject-probability is treated as accept-all"))

(deftest reject-reason-rejects-when-hundred
  (let [reasons (repeatedly 200 #(broker/reject-reason 100))]
    (is (every? some? reasons))
    (is (every? (set broker/reject-reasons) reasons)
        "reason is always one of the documented reasons")))

(deftest reject-message-shape
  (let [msg (broker/reject-message new-order "market-closed")]
    (is (= :broker/order-rejected (:type msg)))
    (is (= "market-closed" (:message msg)))
    (is (= 1 (:order-id msg)))
    (is (= 3 (:account/id msg)))
    (is (s/validate-message msg)
        (pr-str (s/human-error-message msg)))))

(defn- run-broker
  "Drives the paper broker with one new-order and collects the broker's
   updates until `n` messages have been pushed back. Returns the updates."
  [settings n & {:keys [order bid] :or {order new-order bid 100.0M}}]
  (with-redefs [qc/asset-quote-flow (fn [_ _] (test-quotes bid))]
    (let [to-broker (m/rdv)
          from-broker (m/rdv)
          account {:account/id 3 :account/api :paper :account/settings settings}
          task (p/create-trade-account {:quote-manager ::test-quote-manager}
                                       account to-broker from-broker (fn [_]))
          dispose (task (fn [_]) (fn [_]))
          program (m/sp
                   (m/? (to-broker order))
                   (loop [acc []]
                     (if (= n (count acc))
                       acc
                       (recur (conj acc (m/? from-broker))))))
          updates (m/? program)]
      (dispose)
      updates)))

(deftest rejects-without-confirm-or-fill
  (testing "reject-probability 100 emits only :broker/order-rejected"
    (let [[update] (run-broker {:reject-probability 100
                                :fill-probability 100
                                :fill-qty-prct [100]
                                :wait-seconds 0}
                               1)]
      (is (= :broker/order-rejected (:type update)))
      (is (= 1 (:order-id update)))
      (is (contains? (set broker/reject-reasons) (:message update))
          "message is one of the documented reject reasons"))))

(deftest accepts-and-fills-when-not-rejected
  (testing "reject-probability 0 confirms then fills"
    (let [updates (run-broker {:reject-probability 0
                               :fill-probability 100
                               :fill-qty-prct [100]
                               :wait-seconds 0}
                              2)
          types (map :type updates)
          confirmed (first updates)
          fill (second updates)]
      (is (= :broker/order-confirmed (first types)))
      (is (= :limit (:order-type confirmed)))
      (is (= 100.0M (:limit confirmed)))
      (is (some #(= :broker/order-filled %) types))
      (is (= 100.0M (:price fill)))
      (is (not-any? #(= :broker/order-rejected %) types)))))

(deftest echoes-campaign-and-label-on-confirm
  (let [order (assoc new-order :campaign "fx-q2" :label :hedge)
        updates (run-broker {:reject-probability 0
                             :fill-probability 0
                             :fill-qty-prct [100]
                             :wait-seconds 0}
                            1
                            :order order)
        confirmed (first updates)]
    (is (= "fx-q2" (:campaign confirmed)))
    (is (= :hedge (:label confirmed)))
    (is (s/validate-message confirmed))))

(deftest market-order-confirms-without-limit-and-fills-at-bid
  (testing "market order confirm omits :limit; fill price is quote bid"
    (let [updates (run-broker {:reject-probability 0
                               :fill-probability 100
                               :fill-qty-prct [100]
                               :wait-seconds 0}
                              2
                              :order market-order
                              :bid 95.5M)
          confirmed (first updates)
          fill (second updates)]
      (is (= :broker/order-confirmed (:type confirmed)))
      (is (= :market (:order-type confirmed)))
      (is (not (contains? confirmed :limit)))
      (is (s/validate-message confirmed))
      (is (= :broker/order-filled (:type fill)))
      (is (= 95.5M (:price fill))))))

(def ^:private test1-asset "_TEST1")

(defn- bid-ramp-prices
  "100..200 ascending, then 199..50 descending (step ±1)."
  []
  (concat (range 100 201) (range 199 49 -1)))

(defn- bid-ramp-flow
  "Shared discrete quote flow: emit bid every 100ms for _TEST1."
  []
  (m/stream
   (m/ap
    (let [p (m/?> (m/seed (bid-ramp-prices)))
          bid (bigdec p)]
      (m/? (m/sleep 100))
      {:asset test1-asset
       :bid bid
       :ask (+ bid 0.01M)
       :ts (t/instant)}))))

(defn- in-range? [price lo hi]
  (and (some? price) (<= lo price hi)))

(defn- log-fn [msg]
  (println (str "[LOG] " (pr-str msg))))

(deftest market-stop-limit-fill-order-on-bid-ramp
  (testing "market fills near 100, stop near 150–160, limit near 80 after both"
    (let [shared-quotes (bid-ramp-flow)
          settings {:reject-probability 0
                    :fill-probability 100
                    :fill-qty-prct [100]
                    :wait-seconds 0}
          market {:type :trader/new-order
                  :account/id 3
                  :order-id 1
                  :asset test1-asset
                  :side :buy
                  :order-type :market
                  :qty 100M}
          limit {:type :trader/new-order
                 :account/id 3
                 :order-id 2
                 :asset test1-asset
                 :side :buy
                 :order-type :limit
                 :limit 80.0M
                 :qty 100M}
          stop {:type :trader/new-order
                :account/id 3
                :order-id 3
                :asset test1-asset
                :side :buy
                :order-type :stop
                :limit 150.0M
                :qty 100M}]
      (with-redefs [qc/asset-quote-flow (fn [_ _] shared-quotes)]
        (let [to-broker (m/rdv)
              from-broker (m/rdv)
              account {:account/id 3 :account/api :paper :account/settings settings}
              task (p/create-trade-account {:quote-manager ::test-quote-manager}
                                           account to-broker from-broker log-fn)
              dispose (task (fn [_]) (fn [e] (println "[broker-task error]" e)))
              acc (atom [])
              ;; consume broker pushes concurrently so order sends do not deadlock on rdv
              dispose-collect
              ((m/sp
                (loop []
                  (let [msg (m/? from-broker)]
                    (println (str "[UPDATE] " (pr-str msg)))
                    (swap! acc conj msg)
                    (when (< (count @acc) 6)
                      (recur)))))
               (fn [_] (println "[collect] done"))
               (fn [e] (println "[collect] error" e)))
              _ (Thread/sleep 50)
              _ (m/? (m/sp
                      (println "sending market order")
                      (m/? (to-broker market))
                      (println "sending limit order")
                      (m/? (to-broker limit))
                      (println "sending stop order")
                      (m/? (to-broker stop))
                      ;; wait until collector has 3 confirms + 3 fills
                      (loop []
                        (when (< (count @acc) 6)
                          (m/? (m/sleep 50))
                          (recur)))))
              updates @acc
              _ (dispose-collect)
              _ (dispose)
              fills (->> updates
                         (filter #(= :broker/order-filled (:type %)))
                         (sort-by :date)
                         vec)
              by-id (into {} (map (juxt :order-id identity) fills))
              market-fill (get by-id 1)
              limit-fill (get by-id 2)
              stop-fill (get by-id 3)]
          (is (= 3 (count fills)) "one fill per order")
          (is (every? #(= test1-asset (:asset %)) fills))
          (is (every? #(= :buy (:side %)) fills))
          (is (every? #(== 100M (:qty %)) fills))
          (is (in-range? (:price market-fill) 99M 105M)
              (str "market fill near 100, got " (:price market-fill)))
          (is (in-range? (:price stop-fill) 150M 160M)
              (str "stop fill in 150–160, got " (:price stop-fill)))
          (is (in-range? (:price limit-fill) 78M 82M)
              (str "limit fill near 80, got " (:price limit-fill)))
          (is (= [1 3 2] (mapv :order-id fills))
              "fill order: market, then stop, then limit"))))))
