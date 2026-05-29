(ns quanta.market.util
  (:require
   [taoensso.timbre :as timbre :refer [debug info warn error]]
   [missionary.core :as m])
  (:import
   [missionary Cancelled]
   [java.util.concurrent.locks ReentrantLock]))

(defn first-match [predicate flow]
  (m/reduce (fn [_r v]
              (debug "first-match check: " v)
              (when (predicate v)
                (debug "success! returning: " v)
                (reduced v)))
            nil
            flow))

#_(defn next-value [flow]
    ; same as current-v .. need to verify.
    (first-match #(not (nil? %)) flow))

(defn always [flow]
  (m/reduce (fn [_r v]
              v)
            nil
            flow))

(defn take-first-non-nil [f]
  ; flows dont implement deref
  (m/eduction
   (remove nil?)
   (take 1)
   f))

(defn current-v
  "gets the first non-nil value from the flow"
  [f]
  (m/reduce (fn [_r v]
              ;(println "current-v: " v)
              v) nil
            (take-first-non-nil f)))

(defn msg-flow [!-a]
  ; without the stream the last subscriber gets all messages
  (m/stream
   (m/observe
    (fn [!]
      (reset! !-a !)
      (fn []
        (reset! !-a nil))))))

(defn flow-sender
  "returns {:flow f
            :send s}
    (s v) pushes v to f."
  []
  (let [!-a (atom nil)]
    {:flow (msg-flow !-a)
     :send (fn [v]
             (when-let [! @!-a]
               (! v)))}))

(defn mix
  "Return a flow which is mixed by flows"
  ; will generate (count flows) processes, 
  ; so each mixed flow has its own process
  [& flows]
  (m/ap (m/?> (m/?> (count flows) (m/seed flows)))))

(defn start-printing [flow label-str]
  (let [print-task (m/reduce (fn [r v]
                               (println label-str " " v)
                               nil)
                             nil flow)]
    (print-task
     #(println "flow-printer completed: " %)
     #(println "flow-printer crashed: " %))))

(defn start-logging [file-name flow]
  (let [print-task (m/reduce (fn [r v]
                               (let [s (with-out-str (println v))]
                                 (spit file-name s :append true))
                               nil)
                             nil flow)]
    (print-task
     #(println "flow-logger completed: " %)
     #(println "flow-logger crashed: " %))))

(defn split-seq-flow [s]
  (m/ap
   (loop [s s]
     (m/amb
      (first s)
      (let [s (rest s)]
        (when (seq s)
          (recur s)))))))

(defn cont
  "converts a discrete flow to a continuous flow. 
    returns nil in the beginning."
  [flow]
  (->> flow
       (m/reductions (fn [r v]
                       (if v v r)) nil)
       (m/relieve {})))

(defonce running-tasks (atom {}))

(defn start!
  "starts a missionary task
   task can be stopped with (stop! task-id).
   useful for working in the repl with tasks"
  [task id]
  (let [dispose! (task
                  #(println "task completed: " %)
                  #(println "task crashed: " %))]
    (swap! running-tasks assoc id dispose!)
    (str "use (stop! " id ") to stop this task.")))

(defn stop!
  "stops a missionary task that has been started with start!
    useful for working in the repl with tasks"
  [task-id]
  (if-let [dispose! (get @running-tasks task-id)]
    (dispose!)
    (println "cannot stop task - not existing!" task-id)))

(defn start-flow-printer!
  "starts printing a missionary flow to the console.
   printing can be stopped with (stop! id) 
   useful for working in the repl with flows."
  [f id]
  (let [print-task (m/reduce (fn [_r v]
                               (println id " " v)
                               nil)
                             nil f)]
    (start! print-task id)))

(defn start-flow-logger!
  "starts logging a missionary flow to a file.
   can be stopped with (stop! id) 
   useful for working in the repl with flows."
  [file-name id f]
  (let [log-task (m/reduce (fn [r v]
                             (let [s (with-out-str (println v))]
                               (spit file-name s :append true))
                             nil)
                           nil f)]
    (start! log-task id)))

(defn wrap-blk [t]
  ; TODO make this work with a task.
  ;(m/via m/blk (m/? t))
  t)

;; lock

(defn rlock []
  (ReentrantLock.))

(defmacro with-lock
  "Executes exprs in an implicit do, while holding the lock of l.
Will release the lock of l in all circumstances."
  {:added "1.0"}
  [l & body]
  `(let [lockee# ~l]
     (try
       (let [locklocal# lockee#]
         (.lock locklocal#)
         (try
           ~@body
           (finally
             (.unlock locklocal#)))))))

;; stolen from electric 

(defn poll-task
  "derive discrete flow from succession of polled values from a task (or mbox)"
  [task]
  #_(m/ap (m/? (m/?> (m/seed (repeat mbox)))))
  (m/ap
   (loop [v (m/? task)]
     (m/amb v (recur (m/? task))))))

(comment
  (m/?
   (first-match #(> % 3)
                (m/seed [1 2 3 4 5 6])))

  (m/? (m/reduce println nil
                 (current-value (m/seed [1 2 3]))))

  (m/? (current-value-task (m/seed [1 2 3])))

  (m/?
   (m/reduce println nil
             (mix (m/seed [1 2 3 4 5 6 7 8]) (m/seed [:a :b :c]))))

  (def l (rlock))

  (with-lock l (+ 1 1))
  (macroexpand '(with-lock l (+ 1 1)))

; 
  )




