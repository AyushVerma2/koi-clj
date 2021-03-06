(ns koi.api
  (:require 
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report]]
   [ring.adapter.jetty :refer [run-jetty]]
   [compojure.api.sweet :as sw :refer [api context
                                       undocumented
                                       GET PUT POST DELETE]]
   [compojure.api.coercion.spec :as spec-coercion]
   [com.stuartsierra.component :as component]
   [ring.middleware.cors :refer [wrap-cors]]
   [compojure.api.coercion.schema :as cos]
   [compojure.route :as route]
   [clojure.java.io :as io]
   [org.httpkit.client :as http]
   [cheshire.core :as che :refer :all]
   [ring.util.http-response :refer [ok header created]]
   [ring.util.http-status :as status]
   [muuntaja.format.json :as json-format]
   [spec-tools.spec :as spec]
   [koi.interceptors :as ki]
   [clojure.spec.alpha :as s]
   [spec-tools.data-spec :as ds]
   [spec-tools.json-schema :as jsc]
   [schema-tools.core :as st]
   [clojure.java.io :as io]
   [koi.middleware.basic-auth :refer [basic-auth-mw]]
   [koi.middleware.authenticated :refer [authenticated-mw]]
   [koi.middleware.token-auth :refer [token-auth-mw]]
   [koi.route-functions.auth.get-auth-credentials :refer [auth-credentials-response]]
   [koi.config :as config :refer [get-config get-remote-agent]]
   [koi.op-handler :as oph]
   [koi.interceptors :as ki])
  (:import [java.util UUID])
  (:gen-class))

(s/def ::operation string?)
(s/def ::jobid string?)
(s/def ::params map?)
(s/def ::payload (s/keys ::req-un [::operation params]))
(s/def ::auth-header string?)
(s/def ::auth-response string?)

(defn koi-routes
  [config]
  (let [registry (ki/middleware-wrapped-handler config)
        {:keys [get-handler list-handler meta-index-handler]} (oph/meta-handler config)]
    (api
         {:swagger
          {:ui "/"
           :spec "/swagger1.json"
           :data {:info {:title "invoke-api "
                         :description "Invoke with Ocean "}
                  :tags [{:name "invoke service", :description "invoke Ocean services"}]}}}

         (context "/api" []
           :tags ["Invoke ocean service"]
           :coercion :spec
           (context "/v1" []
             :coercion :spec

             (context "/meta" []
               :tags ["Meta service"]
               :coercion :spec
               
               (context "/data/:asset-id" []
                 :path-params [asset-id :- string?]
                 :middleware [basic-auth-mw token-auth-mw authenticated-mw]
                 (sw/resource
                  {:get
                   {:summary "Get metadata for operation"
                                        ;:parameters {:body ::params}
                    :responses {200 {:schema spec/any?}
                                201 {:schema spec/any?}
                                404 {:schema spec/any?}
                                500 {:schema spec/any?}}
                    :handler get-handler}}))
               (context "/data/" []
                        :middleware [basic-auth-mw token-auth-mw authenticated-mw ]
                 (sw/resource
                  {:get
                   {:summary "List operations"
                    :responses {200 {:schema spec/any?}
                                201 {:schema spec/any?}
                                404 {:schema spec/any?}
                                500 {:schema spec/any?}}
                    :handler list-handler}}))
               
              (context "/index" []
                        :middleware [basic-auth-mw token-auth-mw authenticated-mw ]
                 (sw/resource
                  {:get
                   {:summary "Get Index "
                    :responses {200 {:schema spec/any?}
                                201 {:schema spec/any?}
                                404 {:schema spec/any?}
                                500 {:schema spec/any?}}
                    :handler meta-index-handler}})) 
               )

             (context "/auth" []

               (POST "/token" {:as request}
                 :tags ["Auth"]
                 :return ::auth-response
                 :header-params [authorization :- ::auth-header]
                 :middleware [basic-auth-mw authenticated-mw]
                 :summary "Returns auth info given a username and password in the '`Authorization`' header."
                 :description "Authorization header expects '`Basic username:password`' where `username:password`
                         is base64 encoded. To adhere to basic auth standards we have to use a field called
                         `username` however we will accept a valid username or email as a value for this key."
                 (auth-credentials-response request)))


             (context "/invoke" []
               :tags ["Invoke service"]
               :coercion :spec
               

               (context "/sync/:operation-id" []
                 :path-params [operation-id :- string?]
                 :middleware [basic-auth-mw token-auth-mw authenticated-mw]
                 (sw/resource
                  {:post
                   {:summary "Run an sync operation"
                    :parameters {:body ::params}
                    :responses {200 {:schema spec/any?}
                                201 {:schema spec/any?}
                                404 {:schema spec/any?}
                                500 {:schema spec/any?}
                                }
                    :handler (oph/invoke-handler registry)}}))

               (context "/async/:operation-id" []
                 :path-params [operation-id :- string?]
                 :middleware [basic-auth-mw token-auth-mw authenticated-mw]
                 (sw/resource
                  {
                   :post
                   {:summary "Run an async operation"
                    :parameters {:body ::params}
                    :responses {200 {:schema spec/any?}
                                201 {:schema spec/any?}
                                404 {:schema spec/any?}
                                500 {:schema spec/any?}}
                    :handler (oph/invoke-async-handler registry)}}))

               (context
                   "/jobs" []
                 :tags ["Job details"]
                 :coercion :spec

                 (context "/:jobid" []
                   :path-params [jobid :- string?]
                   :middleware [basic-auth-mw token-auth-mw authenticated-mw]
                   (sw/resource
                    {:get
                     {:summary "get the status of a job"
                      :responses {200 {:schema spec/any?}
                                  422 {:schema spec/any?}
                                  404 {:schema spec/any?}
                                  500 {:schema spec/any?}}
                      :handler oph/combined-handler}})))
               )
             )

           (context "/status" []
             :tags ["Status "]
             :middleware [basic-auth-mw token-auth-mw authenticated-mw]
             (sw/resource
              {:get
               {:summary "get the status for this agent"
                :responses {200 {:schema spec/any?}
                            422 {:schema spec/any?}
                            404 {:schema spec/any?}
                            500 {:schema spec/any?}}
                :handler oph/server-status-handler}}))

           (context "/ddo" []
             :tags ["DDO"]
             :middleware [basic-auth-mw token-auth-mw authenticated-mw]
             (sw/resource
              {:get
               {:summary "get the ddo for this agent"
                :responses {200 {:schema spec/any?}
                            422 {:schema spec/any?}
                            404 {:schema spec/any?}
                            500 {:schema spec/any?}}
                :handler (oph/ddo-handler config)}}))))))

(defrecord WebServer [port config]
  component/Lifecycle
  (start [this]
    (info " start jetty at " port " config " config)
    (try
      (let [server (run-jetty
                    (-> (koi-routes config)
                        (wrap-cors :access-control-allow-origin #".*"
                                   :access-control-allow-headers #{:accept :content-type :connection :user-agent :referer :accept-encoding :authorization :origin}  
                                   ;:access-control-allow-credentials true
                                   :access-control-allow-methods
                                   [:get :put :post :delete :options])
                        )
                    {:join? false
                     :port (Integer/valueOf (or (System/getenv "port") port))})]
        (assoc this :http-server server))
      (catch Exception e
        (error " got exception starting jetty " (.getMessage e))
        (clojure.stacktrace/print-stack-trace e)
        (assoc this :http-server nil))))
  (stop [this]
    (info " stopping jetty")
    (if-let [server (:http-server this)]
      (do (.stop server)
          (.join server)
          (dissoc this :http-server))
    this)))

(defn new-webserver
  [port]
  (map->WebServer {:port port})
  )

(defn default-system
  [config]
  (let [{:keys [port]} config]
    (component/system-map
     :config config
     :app (component/using
           (new-webserver port)
           {:config :config}))))

(defn -main [& args]
  (component/start (default-system (aero.core/read-config (clojure.java.io/resource "config.edn")))))

(comment

  (def system (default-system (aero.core/read-config (clojure.java.io/resource "config.edn"))))

  (alter-var-root #'system component/start)
  (-> system :app)
  (alter-var-root #'system component/stop)

  (oph/get-handler (-> system :operation-registry :operation-registry)
                   {:route-params {:did "hashing"}}
                   )
  )

