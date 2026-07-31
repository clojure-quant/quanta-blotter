(ns quanta.quote.subscription-cache
  (:require
   [taoensso.timbre :refer [info]]
   [missionary.core :as m]
   [quanta.missionary :refer [mix]]
   [quanta.missionary.task-timbre :refer [start-task!]]
   [quanta.quote.core :as quote]))

(defn start-cache
  "Subscribe to `assets` on `quote-manager` and keep the subscriptions alive
   by consuming a mixed quote flow (reduce that discards values)."
  [quote-manager assets]
  (let [assets (vec assets)]
    (info "starting quote subscription-cache for" assets)
    (if (empty? assets)
      {:dispose! (fn [])
       :assets assets}
      (let [flows (map #(quote/asset-quote-flow quote-manager %) assets)
            mixed (apply mix flows)
            task (m/reduce (fn [_ _] nil) nil mixed)
            dispose! (start-task! task "quote subscription-cache")]
        {:dispose! dispose!
         :assets assets}))))

(defn stop-cache [{:keys [dispose! assets]}]
  (info "stopping quote subscription-cache for" assets)
  (when dispose! (dispose!)))
