(ns quanta.stresstest.runner
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [missionary.core :as m]
   [quanta.blotter.oms.flow.campaign :as campaign]))

(defn- start-consumer! [state flow update-state]
  (let [state-setter-flow (m/ap (let [v (m/?> flow)]
                                  (swap! state update-state v)         
                                  )) 
        task (m/reduce (fn [_ _] nil) nil state-setter-flow)]
    (task (fn [_])
          #(swap! state assoc :error %))))

(defn wait-for-state [{:keys [state campaign timeout-ms]} pred phase]
  (m/sp
   (let [state-task (m/reduce (fn [_ value] value)
                              nil
                              (m/eduction
                               (filter #(or (:error %) (pred %)))
                               (take 1)
                               (m/watch state)))
         result (m/? (m/race state-task
                             (m/sleep timeout-ms ::timeout)))]
     (cond
       (= ::timeout result)
       (throw (ex-info "Timed out"
                       {:campaign campaign
                        :phase phase
                        :timeout-ms timeout-ms}))

       (:error result)
       (throw (:error result))

       :else result))))



(defn- update-working-orders [state working-orders]
  (-> state
      (assoc :working-orders working-orders)
      (update :orders-seen (fnil into #{}) (keys working-orders))))

(defn start-runner
  "Starts campaign-scoped flow consumers and returns a runner map.

   Uses OMS `:combined-flow` (already campaign-tagged); only filters by
   campaign-id. Tests receive `:oms`, `:campaign`, a live `:state` atom,
   and use `wait-for-state` with a predicate and phase keyword."
  [oms {:keys [campaign-id timeout-ms]}]
  (info "starting runner" campaign-id)
  (let [combined-flow (:combined-flow oms)]
    (when-not combined-flow
      (throw (ex-info "OMS has no combined flow" {:oms oms})))
    (let [{:keys [working-order-dict-flow fill-flow open-position-dict-flow]}
          (campaign/campaign-flows combined-flow campaign-id)
          state (atom {:working-orders {}
                       :open-positions {}
                       :fills []})
          disposers [(start-consumer! state working-order-dict-flow update-working-orders)
                     (start-consumer! state fill-flow
                                      #(update %1 :fills conj %2))
                     (start-consumer! state open-position-dict-flow
                                      #(assoc %1 :open-positions %2))]]
      {:oms oms
       :timeout-ms timeout-ms
       :state state
       :campaign campaign-id
       :disposers disposers})))


(defn stop-runner [{:keys [disposers campaign] :as this}]
  (info "stopping runner" campaign)
  (doseq [dispose! disposers]
    (dispose!))
  (info "stopping runner" campaign "done!"))

(defn calc-result-stats [{:keys [state] :as this}]
  (info "calculating result stats..")
  (let [{:keys [working-orders open-positions fills orders-seen]} @state
        working-orders (or working-orders {})
        open-positions (or open-positions {})
        stats {:fill-qty (reduce + 0M (map :fill/qty fills))
               :order-count (count (or orders-seen #{}))
               :active-order-count (count (filter #(= :working (:order/status %)) (vals working-orders)))
               :position-count (count open-positions)
               :open-position-qty (reduce + 0M (map :position/qty (vals open-positions)))}]
    (info "calculating result stats.. done!")
    stats))

(defn run-task-safe [t]
  (m/sp
   (try
     (m/? t)
     nil
     (catch Exception e
       (error "exception running test task " (ex-message e))
       ::exception))))

(defn run [oms runner-opts test-fn test-opts]
  (m/sp
   (let [this (start-runner oms runner-opts)
         expect (:expect test-opts)
         opts (dissoc test-opts :expect)
         start-ts (System/nanoTime)
         r (m/? (m/race (run-task-safe (test-fn this opts))
                        (m/sleep 30000 ::timeout)))
         result (cond
                  (= ::timeout r)
                  (do
                    (error "timeout state: " @(:state this))
                    {:message "timeout 30 seconds."})

                  (= ::exception r)
                  {:message "exception in test task"}

                  :else
                  (let [stats (calc-result-stats this)
                        end-ts (System/nanoTime)
                        runtime-ms (long (/ (- end-ts start-ts) 1000000))]
                    (if (= expect stats)
                      {:message "success"
                       :runtime-ms runtime-ms}
                      {:message "expected different result."
                       :expect expect
                       :result stats
                       :runtime-ms runtime-ms})))]
     (stop-runner this)
     result)))



