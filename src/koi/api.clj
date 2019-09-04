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
   [koi.op-handler :as oph])
  (:import [java.util UUID]
           ;[koi.utils RemoteStorage]
           )
  (:gen-class))

(s/def ::operation string?)
(s/def ::jobid string?)
(s/def ::params map?)
(s/def ::payload (s/keys ::req-un [::operation params]))
(s/def ::auth-header string?)
(s/def ::auth-response string?)

(defn koi-routes
  [operation-registry]
  (api
   {:swagger
    {:ui "/"
     :spec "/swagger1.json"
     :data {:info {:title "invoke-api "
                   :description "Invoke with Ocean "}
            :tags [{:name "invoke service", :description "invoke Ocean services"}]}}}

   (context "/api/v1" []
     :tags ["Invoke ocean service"]
     :coercion :spec

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

     (context "/invoke/:did" []
       :path-params [did :- string?]
       :middleware [token-auth-mw authenticated-mw]
       (sw/resource
        {:post
         {:summary "Run an sync operation"
          :parameters {:body ::params}
          :responses {200 {:schema spec/any?}
                      201 {:schema spec/any?}
                      404 {:schema spec/any?}
                      500 {:schema spec/any?}
                      }
          :handler (partial oph/invoke-handler operation-registry)}}))

     (context "/invokeasync/:did" []
       :path-params [did :- string?]
       :middleware [token-auth-mw authenticated-mw]
       (sw/resource
        {
         :post
         {:summary "Run an async operation"
          :parameters {:body ::params}
          :responses {200 {:schema spec/any?}
                      201 {:schema spec/any?}
                      404 {:schema spec/any?}
                      500 {:schema spec/any?}}
          :handler (partial oph/invoke-handler operation-registry true)}}))

     (context "/jobs/:jobid" []
       :path-params [jobid :- int?]
       :middleware [token-auth-mw authenticated-mw]
       (sw/resource
        {:get
         {:summary "get the status of a job"
          :responses {200 {:schema spec/any?}
                      422 {:schema spec/any?}
                      404 {:schema spec/any?}
                      500 {:schema spec/any?}}
          :handler oph/result-handler}})))))

(defrecord WebServer [port operation-registry]
  component/Lifecycle
  (start [this]
    (info " start jetty at " port)
    (try 
      (let [server (run-jetty
                    (koi-routes (:operation-registry operation-registry))
                    {:join? false
                     :port (Integer/valueOf (or (System/getenv "port") port))})]
        (assoc this :http-server server))
      (catch Exception e
        (error " got exception starting jetty " (.getMessage e))
        (clojure.stacktrace/print-stack-trace e)
        (assoc this :http-server nil)))

    )
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

(defrecord StorageAgent [agent]
  component/Lifecycle

  (start [component]
    (let [ag (:agent agent)
          storage (koi.utils.RemoteStorage. ag) 
          res (assoc component :storage storage)]
      (info ";; Starting storage agent " agent)
      res))

  (stop [component]
    (info ";; Stopping storage agent ")
    (assoc component :storage nil)))

(defn default-system
  [config]
  (let [{:keys [port]} config]
    (component/system-map
     :agent (oph/new-agent config)
     :storage (component/using (map->StorageAgent {})
                               {:agent :agent})
     :operation-registry (component/using
                          (oph/new-operation-registry config)
                          {:agent :agent
                           :storage :storage})
     :app (component/using
           (new-webserver port)
           {:operation-registry :operation-registry}))))

(defn -main [& args]
  (component/start (default-system (aero.core/read-config (clojure.java.io/resource "config.edn")))))

(comment

  (def system (default-system (aero.core/read-config (clojure.java.io/resource "config.edn"))))

  (alter-var-root #'system component/start)
  (-> system :operation-registry :operation-registry)
  (alter-var-root #'system component/stop)
  )

