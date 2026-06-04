(ns quanta.blotter.cli.server
  (:require
   [reitit.ring :as ring]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.response :as response]
   [clj-service.core :as clj :refer [start-clj-services call-fn get-service]]
   [clj-service.browser-id :refer [session-request]]
   [clj-service.executor :as exec]
   [missionary.core :as m]
   [flowy.ring-adapter :refer [flowy-handler-ws]]
   [flowy.jetty-config :refer [jetty-configurator]]))

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

(defn -main [& _args]
  (let [port 9000
        clj (start-clj-services
             {:app-services [; sp 
                             {:fun 'demo.fortune-cookie/get-cookie}
                            ; ap
                             {:fun 'demo.counter/counter-fn :mode :ap}]})
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


