(ns demo.util.orderflow-simulated-rdv
  (:require
   [missionary.core :as m]
   [demo.util.orderflow-simulated :refer [demo-order-action-flow]]))

(defn- flow->pull
  "Convert a missionary flow (e.g. m/ap) into a pull task that yields successive values."
  [order-rdv flow]
  (let [process-flow (m/ap (let [v (m/?> flow)]
                             ;(println "demo order -> rdv: " v)
                             (m/? (order-rdv v))))
        process-t (m/reduce (fn [r v] nil) nil process-flow)
        dispose!  (process-t #(println "process done" %) #(println "process error " %))]
     dispose!))


(defn create-orderflow-simulated-rdv [order-rdv]
  (flow->pull order-rdv demo-order-action-flow))

(comment 
  (defn print-rdv [rdv]
    (m/sp
     (loop []
       (println "print rdv: " (m/? rdv))
       (recur))))
  
  (def x (create-orderflow-simulated-rdv))
  

  (def dispose-print! ((print-rdv (:orderflow-simulated-rdv x)) #(println "print done " %) #(println "print error " %)))


  
  (m/? (x :test2))
  (dispose-print!)
  
  
  
  (def p (flow->pull demo-order-action-flow))
  (def t (print-rdv (:pull p)))
  (t #(println "pull done " %) #(println "pull error " %))

  
  
  )