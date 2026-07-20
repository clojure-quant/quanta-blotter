(ns demo.stresstest
  (:require
   [quanta.stresstest.tests :refer [run-account-tests]]))

(defn run
  "Run account stresstests for `:account-id`, optionally filtered by `:algo`,
   then exit via modular.system."
  [{:keys [account-id algo running-system]}]
  (let [oms (:oms (:oms-server running-system))]
    (run-account-tests oms account-id algo)))
