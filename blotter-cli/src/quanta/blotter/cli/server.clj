(ns quanta.blotter.cli.server
  (:require
   [missionary.core :as m]
   [reitit.ring :as ring]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.response :as response]
   [clj-service.core :as clj :refer [start-clj-services call-fn get-service]]
   [clj-service.browser-id :refer [session-request]]
   [clj-service.executor :as exec]
   [flowy.ring-adapter :refer [flowy-handler-ws]]
   [flowy.jetty-config :refer [jetty-configurator]]
   [demo.fortune-cookie :as cookie]
   ))

(defn prepare-flowy-request [clj req]
  (-> req session-request (assoc :ctx {:clj clj})))

(defn flowy-handler [clj]
  (fn [req]
    (flowy-handler-ws (prepare-flowy-request clj req))))

(defn make-handler [clj]
  (ring/ring-handler
   (ring/router
    [["/" {:handler (fn [_]
                      (response/resource-response "public/index.html"))}]
     ["/ping" {:get (fn [_] {:status 200 :body "pong"})}]
     ["/flowy" {:handler (flowy-handler clj)}]
     ["/r/*" (ring/create-resource-handler)]])
   (ring/routes
    (ring/create-default-handler
     {:not-found (constantly {:status 404 :body "Not found"})}))))

(defn start-socket-server [oms trade-db oms-server]
  (let [port 9000
        cookie-db cookie/db
        clj (start-clj-services
             {:env {:oms oms
                    :trade-db trade-db
                    :oms-server oms-server
                    :cookie-db cookie-db}
              :app-services [; test api 
                             {:fun 'demo.fortune-cookie/get-cookie :ctx :cookie-db}
                             {:fun 'demo.counter/counter-fn :mode :ap}
                             ; oms api
                             {:fun 'quanta.blotter.oms.core/send-test-order
                              :ctx :oms :permission nil :mode :sp}
                             {:fun 'quanta.blotter.oms.core/create-limit-order
                              :ctx :oms :permission nil :mode :sp}
                             {:fun 'quanta.blotter.oms.core/combined-flow 
                              :ctx :oms :permission nil :mode :ap}
                             {:fun 'quanta.blotter.oms.flow.snapshot/trading-snapshot-fn
                              :ctx :oms :permission nil :mode :ap}
                             ; trade db
                             {:fun 'quanta.blotter.oms.db/query-messages
                              :ctx :trade-db :permission nil :mode :clj}
                             {:fun 'quanta.blotter.oms.db/query-orders
                              :ctx :trade-db :permission nil :mode :clj}
                             {:fun 'quanta.blotter.oms.db/query-fills
                              :ctx :trade-db :permission nil :mode :clj}
                             {:fun 'quanta.blotter.oms.db/query-positions
                              :ctx :trade-db :permission nil :mode :clj}
                             ]})
        h (make-handler clj)]
    (println "demo cookie:"
             (call-fn (get-service clj {:fun 'demo.fortune-cookie/get-cookie}) {}))

    (println "demo cookie: " (m/? (exec/execute {:clj clj}
                                                {:user nil  :session nil}
                                                {:fun 'demo.fortune-cookie/get-cookie})))

    (println (str "Starting server on http://localhost:" port))
    (run-jetty h {:join? false
                  :port port
                  :ip "0.0.0.0"
                  :configurator jetty-configurator})))


