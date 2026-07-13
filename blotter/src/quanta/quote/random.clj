(ns quanta.quote.random
  (:require
   [missionary.core :as m]
   [quanta.quote.protocol :as p]
   [quanta.quote.interactor :refer [subscription-watcher]]))

(def default-settings
  {:initial-price 100.0
   :random-change 0.0002
   :trend-change 0.0002
   :trend-clamp 0.004
   :quote-tick-interval-ms 250})

(defn random-return-value [change]
  (* (- (rand change) (/ change 2.0)) 10.0))

(defn- round-2 [n]
  (/ (Math/round (* n 100.0)) 100.0))

(defn clamp [x lo hi]
  (min hi (max lo x)))

(defn next-state [{:keys [trend-change trend-clamp random-change]} {:keys [price trend]}]
  (let [trend (-> (+ trend (random-return-value trend-change))
                  (clamp (- trend-clamp) trend-clamp))
        price (round-2 (* price (+ 1.0 trend (random-return-value random-change))))]
    {:price price :trend trend}))

(defn state-seq [settings start-state]
  (iterate #(next-state settings %) start-state))

(defn update-states [settings d]
  (->> d
       (map (fn [[k v]]
              [k (next-state settings v)]))
       (into {})))

(comment
  (random-return-value 0.0002)
  (take 10 (state-seq default-settings {:price 100.0 :trend 0.0}))
  (->> {"A" {:price 100.0 :trend 0.0}
        "B" {:price 104.0 :trend 0.0}}
       (update-states default-settings)
       (update-states default-settings)
       (update-states default-settings)
       (update-states default-settings))

;
  )

;; update states and send quotes

(defn message-loop [account state-a subscription-a settings log send-quote]
  (let [{:keys [quote-tick-interval-ms]} settings
        symbol->quote (fn [[asset price]]
                        (let [bid (round-2 price)
                              ask (round-2 (+ bid 0.01))]
                          {:account (:account/id account)
                           :asset asset
                           :bid bid
                           :ask ask}))]
    (m/sp
     (loop []
       (let [assets @subscription-a]
         (swap! state-a (fn [d] (merge d (update-states settings (select-keys d assets)))))
         (doseq [asset assets]
           (when-let [price (:price (get @state-a asset))]
             (send-quote (symbol->quote [asset price])))))
       (m/? (m/sleep quote-tick-interval-ms))
       (recur)))))

;; process subscription changes

(defn initial-state [initial-price]
  {:price initial-price :trend 0.0})

(defn repeat-nr [initial-price n]
  (vec (repeatedly n #(initial-state initial-price))))

(defrecord random-msg-processor [account log state-a initial-price]
  p/quote-messaging
  (subscribe-msg [_ sub]
    (swap! state-a (fn [d]
                     (let [new-assets (filter #(not (contains? d %)) sub)]
                       (merge d (zipmap new-assets (repeat-nr initial-price (count new-assets)))))))
    (log {:account (:account/id account) :type :subscribe :assets sub}))
  (unsubscribe-msg [_ unsub]
    ;; keep last simulated state in state-a for later re-subscribe
    (log {:account (:account/id account) :type :unsubscribe :assets unsub}))
  (read-quote [_ fix-vec-in]
    nil))

;; create quote account

(defn dump-rdv [rdv]
  (m/sp
   (loop []
     (m/? rdv)
     (recur))))

(defmethod p/create-quote-account :random
  [account subscription-a send-quote log]
  (let [settings (merge default-settings (:account/settings account))
        push (m/rdv)
        state-a (atom {})
        rmp (random-msg-processor. account log state-a (:initial-price settings))]
    (m/sp
     (log {:type :random-quote-start :account (:account/id account) :settings settings})
     (m/? (m/join vector
                  (subscription-watcher account rmp subscription-a push log)
                  (message-loop account state-a subscription-a settings log send-quote)
                  (dump-rdv push))))))
