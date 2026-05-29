(ns demo.util.update-printer
  (:require
   [missionary.core :as m]))

(defn print-orderupdate [rdv]
  (m/sp
   (loop []
     (println "orderupdate: " (m/? rdv))
     (recur))))

(defn create-orderupdate-printer []
  (let [r (m/rdv)
        print-t (print-orderupdate r)]
    {:orderupdate-rdv  r
     :dispose-orderupdate-printer (print-t #(println "orderupdate-print done " %) #(println "orderupdate-print error " %))}))
