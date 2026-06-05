(ns quanta.blotter.util-rdv-test
  (:require
   [clojure.test :refer [deftest is]]
   [quanta.blotter.util-rdv :refer [create-rdv]])
  (:import
   [missionary Cancelled]))

(deftest give-cancel-includes-name-test
  (let [rdv (create-rdv "order-rdv")
        err (atom nil)
        give (rdv 42)
        dispose (give (fn [_] nil) (fn [e] (reset! err e)))]
    (dispose)
    (is (instance? Cancelled @err))
    (is (= "Rendez-vous give order-rdv cancelled." (.getMessage ^Cancelled @err)))))

(deftest take-cancel-includes-name-test
  (let [rdv (create-rdv "orderupdate-rdv")
        err (atom nil)
        dispose (rdv (fn [_] nil) (fn [e] (reset! err e)))]
    (dispose)
    (is (instance? Cancelled @err))
    (is (= "Rendez-vous take orderupdate-rdv cancelled." (.getMessage ^Cancelled @err)))))

(deftest transfer-still-works-test
  (let [rdv (create-rdv "xfer")
        result (atom nil)]
    (let [give (rdv 42)]
      (give (fn [_] nil) (fn [e] (reset! result [:give-err e])))
      (rdv (fn [v] (reset! result [:take v])) (fn [e] (reset! result [:take-err e]))))
    (is (= [:take 42] @result))))
