(ns coney.core
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.data.codec.base64 :as b64]
            [digest]
            [org.httpkit.client :as http]
            [cheshire.core :as cheshire])
)

; http://stackoverflow.com/a/10065003
(defn hexify [s]
  (apply str (map #(format "%02x" %) s)))

(defn unhexify [s]
  (let [bytes (into-array Byte/TYPE
                 (map (fn [[x y]]
                    (unchecked-byte (Integer/parseInt (str x y) 16)))
                       (partition 2 s)))]
    bytes))

; based on https://gist.github.com/christianclinton/faa1aef119a0919aeb2e
(defn decode_rabbit_password_hash [password_hash]
  (let [password_hash (b64/decode (.getBytes password_hash))
        decoded_hash (hexify password_hash)]
        [(apply str (take 8 decoded_hash)) (apply str (drop 8 decoded_hash))]
  )
)

(defn encode_rabbit_password_hash [salt password]
  (let [salt_and_password (unhexify (str salt (hexify (.getBytes password "UTF-8"))))
        salted_md5 (digest/md5 salt_and_password)]
    (String. (b64/encode (unhexify (str salt salted_md5))))
  )
)

(defn check_rabbit_password [test_password password_hash]
  (let [[salt hash_md5sum] (decode_rabbit_password_hash password_hash)
        test_password_hash (encode_rabbit_password_hash salt test_password)]
    (= test_password_hash password_hash)
  )
)

(def root "http://localhost:15672/api/")

(defn get-key-from-hash [key hash]
  (hash-map (keyword (key hash)) hash)
)

(defn get-name-from-hash [hash]
  (get-key-from-hash :name hash)
)

(defn get-user-from-hash [hash]
  (get-key-from-hash :user hash)
)

(apply merge (map get-name-from-hash (-> @(http/get (str root "users") {:basic-auth ["guest" "guest"]}) :body (cheshire/decode true))))

(def core-params {:basic-auth ["guest" "guest"] :headers {"content-type" "application/json"}})

(defn expected-code [resp code]
  (if (not= (:status resp) code)
    (do
      (println resp)
      (throw (RuntimeException. (str resp)))
    )
  )
)

(defn- get-x-from-api [get-func api-key]
   (apply merge (map get-func (-> @(http/get (str root api-key) core-params) :body (cheshire/decode true))))
)

(defn get-names-from-api [api-key]
  (get-x-from-api get-name-from-hash api-key)
)

(defn get-users-from-api [api-key]
  (get-x-from-api get-user-from-hash api-key)
)

(defn get-names-from-hash [key config]
  (apply merge (map get-name-from-hash (key config)))
)

(defn get-users-from-hash [key config]
  (apply merge (map get-user-from-hash (key config)))
)

(defn sync-config [kind existing wanted vhost sync-keys]
	(let [vhost-encoded (http/url-encode (name vhost))]
		(doseq [item (keys wanted)
				:let [wanted-keys (select-keys (item wanted) sync-keys)]]
			(if (or (not (contains? existing item)) (not= wanted-keys (select-keys (item existing) sync-keys)))
				(do
					(println "missing/wrong" kind (name item))
					(expected-code @(http/put
								   (str root kind "/" vhost-encoded "/" (name item))
								   (merge core-params {:body (cheshire/encode wanted-keys)}))
								 204)
				)
			)
		)
	)
)

(defn sync-bindings [kind existing wanted vhost sync-keys]
	(let [vhost-encoded (http/url-encode (name vhost))]
		(doseq [item (keys wanted)
				:let [wanted-item (item wanted)
              wanted-keys (select-keys wanted-item sync-keys)]]
			(if (or (not (contains? existing item)) (not= wanted-keys (select-keys (item existing) sync-keys)))
				(do
					(println "missing/wrong bindings" (name item))
					(expected-code @(http/post
								   (str root "bindings/" vhost-encoded "/e/" (:source wanted-item) "/q/" (:destination wanted-item))
								   (merge core-params {:body (cheshire/encode wanted-keys)}))
								 201)
				)
			)
		)
	)
)

(defn get-bindings-from-hash [hash]
  (hash-map (keyword (clojure.string/join "-" (map #(% hash) [:destination :destination_type :arguments :routing_key :source]))) hash)
)

(defn -main
  [& args]
  (let [config (-> args first slurp edn/read-string)
        existing-users (get-names-from-api "users")
        wanted-users (get-names-from-hash :users config)
        existing-vhosts (map #(keyword (:name %)) (-> @(http/get (str root "vhosts") core-params) :body (cheshire/decode true)))
        wanted-vhosts (get-names-from-hash :vhosts config)
        ]
    (doseq [user (keys wanted-users)]
      (if (or (not (contains? existing-users user)) (not (check_rabbit_password (:password (user wanted-users)) (:password_hash (user existing-users)))))
        (do
          (println "missing" user)
          (expected-code @(http/put
                           (str root "users/" (name user))
                           (merge core-params {:body (cheshire/encode {:tags "" :password (:password (user wanted-users))})}))
                         204)
          )
        )
    )
    (doseq [vhost (keys wanted-vhosts)]
      (if (not (.contains existing-vhosts vhost))
        (do
          (println "missing vhost" vhost)
          (expected-code @(http/put
                           (str root "vhosts/" (name vhost))
                           core-params)
                         204)
          )
      )
      (let [vhost-encoded (http/url-encode (name vhost))
            vhost-config (vhost wanted-vhosts)
            ]
        (sync-config "permissions"
                     (get-users-from-api "permissions")
                     (get-users-from-hash :permissions vhost-config) vhost
                     [:configure :write :read])
        (sync-config "queues"
                     (get-names-from-api (str "queues/" vhost-encoded))
                     (get-names-from-hash :queues vhost-config) vhost
                     [:arguments :durable :auto-delete])
        (sync-config "exchanges"
                     (get-names-from-api (str "exchanges/" vhost-encoded))
                     (get-names-from-hash :exchanges vhost-config) vhost
                     [:arguments :internal :type :auto_delete :durable])
        (sync-bindings "bindings"
                     (get-x-from-api get-bindings-from-hash "bindings")
                     (apply merge (map get-bindings-from-hash (:bindings vhost-config)))
                     vhost
                     [:destination :destination_type :arguments :routing_key :source]
        )
      )
    )
    (println "All done")
  )
)
