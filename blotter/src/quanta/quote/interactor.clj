(ns quanta.quote.interactor
  (:require
   [clojure.set :refer [difference]]
   [missionary.core :as m]
   [quanta.quote.protocol :as p])
  (:import missionary.Cancelled))

(defn- sub-unsub-sets [old new]
  (let [unsub (difference old new)
        sub (difference new old)]
    {:sub sub :unsub unsub}))

(comment
  (sub-unsub-sets #{1 2 3} #{2 3 4})
  ;
  )

(defn process-subscription-changes [account quote-message-processor subscription-f push session-log]
  (m/ap
   (let [assets-old (atom #{})
         assets-new (m/?> 1 subscription-f)
         _  (session-log {:type :subscriptions :assets assets-new :account (:account/id account)})
         {:keys [sub unsub]} (sub-unsub-sets @assets-old assets-new)]
     (reset! assets-old assets-new)
       ; subscribe
     (when (seq sub)
       (println "subscription-watcher subscribing to: " sub)
       (let [msg (p/subscribe-msg quote-message-processor sub)]
         (m/? (push msg))))
       ; unsubscribe
     (when (seq unsub)
       (println "subscription-watcher unsubscribing from: " unsub)
       (let [msg (p/unsubscribe-msg quote-message-processor unsub)]
         (m/? (push msg)))))))

(defn subscription-watcher
  [account quote-message-processor subscription-a push session-log]
  (let [sub-f (m/watch subscription-a)
        sub-f (m/relieve sub-f)
        sub-process-f (process-subscription-changes account quote-message-processor sub-f push session-log)]
    (m/reduce (fn [_ _] nil) nil sub-process-f)))

(defn- message-loop
  [quote-message-processor pull log send-quote]
  (m/sp
   (try
     (loop []
       (when-let [fix-payload (m/? (pull))]
         (when-let [normalized (p/read-quote quote-message-processor fix-payload)]
           (send-quote normalized)))
       (recur))
     (catch Cancelled _
       true))))

(defn create-quote-interactor
  "quote-rdv: missionary rendezvous from (m/rdv); consumer takes with (m/? quote-rdv),
   producer gives with (m/? (quote-rdv value))."
  [subscription-a send-quote]
  (fn [account _connection-id push pull log asset-converter]
    (let [quote-message-processor (p/create-quote-messaging account asset-converter log)]
      (m/sp
       (log {:type :interactor-start})
       (m/? (m/join vector
                    (subscription-watcher account quote-message-processor subscription-a push log)
                    (message-loop quote-message-processor pull log send-quote)))))))