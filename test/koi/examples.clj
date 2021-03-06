(ns koi.examples
  (:require [clojure.test :as t]
            [starfish.core :as s]
            ;;to be removed
            [sieppari.core :as si]
            [koi.utils :refer [resolve-op]]
            [koi.op-handler :as oph]
            [koi.interceptors :as ki]
            [clojure.data.json :as json]
            [koi.config :as cf]
            [clojure.java.io :as io])
  (:import [org.json.simple.parser JSONParser]))

;;the spec for an invokable function requires that
;;
;;it takes a map as input. Each key must be a keyword and value must be a
;; startfish asset or JSON object.
;;it returns a map as output, which follows the same convention
;;here's an example of such an operation that computes the sha3 hash of a string

(defn sha-raw-hash
  "accepts a JSON object input against the to-hash key, and returns the hash value as a string"
  [{:keys [to-hash]}]
  {:hash-val (s/digest to-hash)})

(defn sha-asset-hash
  "accepts a starfish asset against the to-hash key, and returns a starfish asset as the value
  against the hash-val key"
  [{:keys [to-hash]}]
  {:hash-val (s/asset (s/memory-asset {"meta" "data"}
                                      (-> to-hash s/content s/to-string s/digest)))})

;;comment out to avoid evaluation
(comment

  (sha-raw-hash {:to-hash "abcd"})
  ;;returns
  ;;{:hash-val "48bed44d1bcd124a28c27f343a817e5f5243190d3c52bf347daf876de1dbbf77"}


  ;;Execute a similar operation defined in a separate namespace.
  ;;it consists of a namespace followed by the `defn`.

  ;;this particular operation accepts a map where values are Starfish assets,
  ;;and returns a map where the values are again Starfish assets.
  (-> "koi.examples/sha-asset-hash"
      (resolve-op)
      (as-> f (f {:to-hash (s/memory-asset {:test :metadata} "content")})))

  ;;In order to publish the operation, it requires that the operation metadata must be defined
  ;;so that callers know what keys to provide in inputs, and know what keys would be returned in the output.
  ;;here's an example of a metadata definition for the hash operation

  ;;returns a map, where :params are the input
  ;;note that it needs a single key 'to-hash', the value of which is an asset

  ;;{:modes ["sync" "async"],
  ;; :params {:to-hash {:type "asset", :position 0, :required true}},
  ;; :results {:hash-value {:type "asset"}}}

  ;;We use Koi interceptors to perform useful functionality before and after
  ;;the operation is called.

  ;;when requests come in "over the wire", the DEP6 defines
  ;;as 'asset' as a map that contains a :did key and a value that's a string
  ;;an input-asset-retrieval is an interceptor that replaces the did
  ;;with a Starfish Asset
  ;;using a local retrieval-function as an example
  (let [ret-fn {"did:op:1234/4567" (s/memory-asset
                                    {:meta :data}
                                    "content")}]
    (->> {:to-hash {:did "did:op:1234/4567"}}
         ((ki/run-chain
           [(ki/input-asset-retrieval ret-fn)]
           sha-asset-hash))))

  ;;lets run the same with a remote agent
  (let [agent-conf {:agent-url "http://13.70.20.203:8090"
                    :username "Aladdin"
                    :password "OpenSesame"}
        ragent (:remote-agent (cf/get-remote-agent agent-conf))
        ;;lets pre-register an asset that will be used
        test-input-asset (s/asset (s/memory-asset
                                        ;{"test" "metadata"}
                                   "content"))
        asset-id (do (s/register ragent test-input-asset)
                     (s/asset-id (s/upload ragent test-input-asset)))
        ret-fn (partial s/get-asset ragent)]
    (->> {:to-hash {:did asset-id}}
         ((ki/run-chain
           [(ki/input-asset-retrieval ret-fn)]
           sha-asset-hash))))

  ;;this returns a map where the values are Asset objects.
  ;;Let's use the output-asset-upload interceptor to
  ;;register and upload assets generated by the operation.
  ;;this allows the user to specify one or more agents, as the source and sink
  ;;for assets.
  ;;in this case, the same remote agent is used.
  (let [agent-conf {:agent-url "http://13.70.20.203:8090"
                    :username "Aladdin"
                    :password "OpenSesame"}
        remote-agent (cf/get-remote-agent agent-conf)
        ragent (:remote-agent remote-agent)
        ;;lets pre-register an asset that will be used
        test-input-asset (s/asset (s/memory-asset
                                   {"test" "metadata"}
                                   "content"))
        upload-fn (ki/asset-reg-upload ragent)
        asset-id (upload-fn test-input-asset )
        ret-fn (partial s/get-asset ragent)]
    (->> {:to-hash {:did asset-id}}
         ((ki/run-chain
           [(ki/output-asset-upload upload-fn)
            (ki/input-asset-retrieval ret-fn)
            ]
           sha-asset-hash))))

  ;;run as middleware where the function is passed in the operation registry
  ;;this example includes config for
  ;;a)the actualy function hander
  ;;b)the agent config for retrieving
  ;;c)agent config for registering and uploading assets
  ;;d)specification for inputs to the handler
  ;;e)specification for results returned from the handler
  (let [op-registry {:hashing {:handler "koi.examples/sha-asset-hash"}}
        agent-conf {:agent-url "http://13.70.20.203:8090"
                    :username "Aladdin"
                    :password "OpenSesame"}
        param-spec {:hash-val {:type "asset", :position 0, :required true}}
        result-spec {:to-hash {:type "asset", :position 0, :required true}}
        remote-agent (cf/get-remote-agent agent-conf)
        ragent (:remote-agent remote-agent)
        ;;lets pre-register an asset that will be used
        test-input-asset (s/asset (s/memory-asset
                                   {"test" "metadata"}
                                   "content"))

        upload-fn (fn[ast]
                    (do (s/register ragent ast)
                        (s/asset-id (s/upload ragent ast))))
        asset-id (upload-fn test-input-asset )
        ret-fn (partial s/get-asset ragent)]
    (->> {:to-hash {:did asset-id}}
         ((ki/run-chain
           [(ki/input-asset-retrieval ret-fn)
            (ki/param-validator param-spec)
            (ki/result-validator result-spec)
            (ki/output-asset-upload upload-fn)]
           (ki/materialize-handler (get-in op-registry [:hashing :handler]))
           ))))


  ;;run the same as above, but load the entire configuration from a single map
  (def config
    {:operation-registry
     {:hashing {:handler "koi.examples/sha-asset-hash"
                :metadata
                {:results {:hash-val {:type "asset", :position 0, :required true}}
                 :params {:to-hash {:type "asset", :position 0, :required true}}}}}

     :agent-conf
     {:agent-url "http://13.70.20.203:8090"
      :username "Aladdin"
      :password "OpenSesame"}})

  (let [test-input-asset (s/asset (s/memory-asset
                                   {"test" "metadata"}
                                   "content"))
        remote-agent (cf/get-remote-agent (:agent-conf config))
        ragent (:remote-agent remote-agent)

        asset-id ((ki/asset-reg-upload ragent) test-input-asset)
        wrapped-handler (ki/middleware-wrapped-handler config)
        op-handler (wrapped-handler :hashing)
        ]
    (when op-handler
      (op-handler {:to-hash {:did asset-id}})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;;testing the ring handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (let [test-input-asset (s/asset (s/memory-asset
                                   {"test" "metadata"}
                                   "content"))
        remote-agent (cf/get-remote-agent (:agent-conf config))
        ragent (:remote-agent remote-agent)

        asset-id ((ki/asset-reg-upload ragent) test-input-asset)
        wrapped-handler (ki/middleware-wrapped-handler config)
        ]
    ((oph/invoke-handler wrapped-handler)
     {:body-params {:to-hash {:did asset-id}}
      :route-params {:did :hashing}}))

  )
