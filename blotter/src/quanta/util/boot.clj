(ns quanta.util.boot
  (:require
   [missionary.core :as m]
   [quanta.util.session :as session]))

(defn- dbg [& args]
  (apply println "[boot]" args)
  (flush))

(defn forever
  "Runs `task` repeatedly (one at a time; restarts after each completion)."
  [task]
  (m/ap (m/? (m/?> 1 (m/seed (repeat task))))))

(defn- fib-iter [[a b]]
  (case b
    0 [1 1]
    [b (+ a b)]))

(def ^:private fib (map first (iterate fib-iter [1 1])))
(def ^:private retry-delays (map (partial * 100) (next fib)))

(defn boot-with-retry
  "Connects with fibonacci backoff and runs fix-session until disconnect or failure.
   `account` is a map with `:account/api` `:fix` and `:account/settings`.
   `log` is a function of one event map (created by the caller with flow-sender)."
  [account log interactor]
  (m/sp
   (dbg "boot-with-retry: started")
   (loop [delays retry-delays n 1]
     (dbg "boot-with-retry: attempt" n)
     (if-some [exit (m/? (session/connect-and-run account log interactor))]
       (let [next-delays (case exit
                           :host-unknown nil
                           :connect-ex delays
                           :run-finally (seq retry-delays)
                           :cancelled nil
                           delays)]
         (dbg "boot-with-retry: exit=" exit "will-retry?" (boolean next-delays))
         (if-some [backoff-ms (first next-delays)]
           (do
             (dbg "boot-with-retry: sleeping " backoff-ms " ms before retry")
             (m/? (m/sleep backoff-ms))
             (recur (rest next-delays) (inc n)))
           (dbg "boot-with-retry: done, no more retries")))
       (dbg "boot-with-retry: connect returned nil")))))
