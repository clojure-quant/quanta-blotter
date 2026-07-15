(ns demo.stresstest
  (:require
   [missionary.core :as m]
   [quanta.stresstest.tests :refer [run-account-tests]]))

(defn run
  "Run account stresstests for `:account-id`, then exit via modular.system."
  [{:keys [account-id running-system]}]
  (let [oms (:oms (:oms-server running-system))]
    (m/? (run-account-tests oms account-id))))
