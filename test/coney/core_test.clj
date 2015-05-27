(ns coney.core-test
  (:require [coney.core :as core])
  (:use [midje.sweet]
        [org.httpkit.fake])
)

(facts "Expected code works"
  (fact "Right status works"
      (core/expected-code {:status 1} 1) => nil
  )
  (fact "Wrong status works"
      (with-out-str (core/expected-code {:status 1} 2)) => (throws RuntimeException)
  )
  (fact "No status works"
    (with-out-str (core/expected-code {} 1)) => (throws RuntimeException)
  )
)

(fact "get-users-from-hash returns empty"
  (core/get-users-from-hash :foo {}) => {})

(fact "Get-bindings works"
  (core/get-bindings-from-hash {:destination "orders.hoxton",
         :destination_type "queue",
         :arguments {},
         :routing_key "",
         :source "orders"}) =>
      {(keyword "orders.hoxton-queue-{}--orders")
       {:arguments {}, :destination "orders.hoxton", :destination_type "queue", :routing_key "", :source "orders"}
       }
)

(fact "Get names works"
  (with-fake-http ["http://localhost:15672/api/users" "[{\"name\":\"bar\",\"password_hash\":\"foo\",\"tags\":\"\"}]"]
    (core/get-names-from-api "users")
  ) => {:bar {:name "bar", :password_hash "foo", :tags ""}}
)

(defn no-vhost
  ([] (no-vhost "localhost"))
  ([hostname]
  (fn [request]
    (contains? #{
                 (str "http://" hostname ":15672/api/users")
                 (str "http://" hostname ":15672/api/permissions")
                 (str "http://" hostname ":15672/api/bindings")
                 (str "http://" hostname ":15672/api/vhosts")}
               (:url request))
    ))

)

(defn for-vhost [vhost]
  (fn [request]
    (contains?
     #{ (str "http://localhost:15672/api/queues/" vhost)
        (str "http://localhost:15672/api/exchanges/" vhost)
        (str "http://localhost:15672/api/bindings/" vhost)
      }
     (:url request)
    )
  )
)

(defmacro run-with-file [content & func]
  `(with-out-str
    (with-redefs
      [
       slurp (fn [& _#] ~content)
       core/file-exists (fn [path#] true)
      ]
        ~@func
    )
  )
)

(fact "main"
  (with-state-changes [
                       (around :facts (with-redefs
                                        [core/exit (fn [code msg] (println (format "Exit with arg: %d and msg '%s'" code msg)))]
                                        ?form ))]

    (fact "Does help" (with-out-str (core/-main "--help")) => (every-checker (contains "Exit with arg: 0") (contains "Usage") (contains "--filetype FILETYPE")))

    (fact "Needs an argument" (with-out-str (core/-main)) => (contains "Need a single file argument"))

    (fact "Copes with bad arguments" (with-out-str (core/-main "--garbage")) => (contains "Unknown option: \"--garbage\""))

    (fact "Argument file must exist" (with-out-str (with-redefs [slurp (fn [& _] (throw (java.io.FileNotFoundException.)))
                                      core/file-exists (fn [path] false)]
                          (core/-main "Foo")
                          )) => (contains "No such file 'Foo'"))

    (fact "Copes with bad filetype" (run-with-file "" (core/-main "--filetype" "bar" "Foo"))
                           => (every-checker (contains "Don't know file format 'bar'") (contains "Exit with arg: 1")))

    (fact "Copes with bad edn" (run-with-file "{\"json\": \"not-edn\"}" (core/-main "--filetype" "edn" "Foo"))
                           => (every-checker (contains "Bad EDN file 'Foo'") (contains "Exit with arg: 1")))

    (fact "Does alternate host"
          (with-fake-http [(no-vhost "other-host") "{}"]
            (run-with-file "{}" (core/-main "--host" "other-host" "Foo"))
          )
            => "All done\n")

    (fact "Does alternate login"
          (with-fake-http [#(if (= (:basic-auth %) ["test_user" "test_password"]) true (throw (Exception. (format "Auth was %s" (:basic-auth %)))))
                           (fn [orig-fn opts callback] {:status 200})]
            (run-with-file "{}" (core/-main "--username" "test_user" "--password" "test_password" "Foo"))
          )
            => "All done\n")

    (fact "Does Users"
        (with-fake-http [(no-vhost) "{}"
                         {:method :put :url "http://localhost:15672/api/users/customer"} {:status 204}
                         {:method :put :url "http://localhost:15672/api/users/chef"} {:status 204}]
          (run-with-file
            "{:users
             [{:name \"customer\",
             :password \"customer\"}
             {:name \"chef\",
             :password \"chef\"}]}"
            (core/-main "--filetype" "edn" "Foo")) => (every-checker (contains "missing user 'customer'") (contains "missing user 'chef'"))))

    (fact "Does Users with no password"
        (with-fake-http [(no-vhost) "{}"
                         {:method :put :url "http://localhost:15672/api/users/chef"} {:status 204}]
          (run-with-file
            "{:users
             [{:name \"chef\"}]}"
            (core/-main "--filetype" "edn" "Foo")) => (contains "missing password for user 'chef'")))

    (fact "Does JSON"
        (with-fake-http [(no-vhost) "{}"
                         {:method :put :url "http://localhost:15672/api/users/customer"} {:status 204}
                         {:method :put :url "http://localhost:15672/api/users/chef"} {:status 204}]
          (run-with-file "{
                     \"users\" :
                     [{\"name\" : \"customer\",
                     \"password\" : \"customer\"},
                     {\"name\" : \"chef\",
                     \"password\" : \"chef\"}]}"
            (core/-main "--filetype" "json" "Foo"))) => (every-checker (contains "missing user 'customer'") (contains "missing user 'chef'")))

    (fact "Does export-style JSON"
      (fact "for exchanges"
        (with-fake-http [(no-vhost) "{}"
                         "http://localhost:15672/api/exchanges/foo" {}
                         {:method :put :url "http://localhost:15672/api/exchanges/foo/orders-topic"} {:status 204}]
          (run-with-file "{\"exchanges\" : [ {
         \"vhost\" : \"foo\",
         \"durable\" : true,
         \"internal\" : false,
         \"arguments\" : {},
         \"type\" : \"topic\",
         \"auto_delete\" : false,
         \"name\" : \"orders-topic\"}]}"
            (core/-main "--filetype" "json" "Foo"))) => (every-checker (contains "missing/wrong exchanges for 'orders-topic' on 'foo'")))

      (fact "for Permissions"
            (with-fake-http [(no-vhost) "{}"
                             (for-vhost "some-vhost") "{}"
                             {:method :put :url "http://localhost:15672/api/permissions/some-vhost/chef"} {:status 204}]
          (run-with-file "{ \"permissions\": [{
                               \"configure\" : \"\",
                               \"write\": \"\",
                               \"user\": \"chef\",
                               \"read\": \"orders\",
                               \"vhost\": \"some-vhost\"
                               }]}"
              (core/-main "--filetype" "json" "Foo"))) => (contains "missing/wrong permissions for 'chef'"))

      (fact "for Queues"
          (with-fake-http [(no-vhost) "{}"
                           (for-vhost "some-vhost") "{}"
                           {:method :put :url "http://localhost:15672/api/queues/some-vhost/orders.hoxton"} {:status 204}]
            (run-with-file "{\"queues\": [{
                           \"name\" :\"orders.hoxton\",
                             \"arguments\": \"{}\",
                             \"durable\" : true,
                             \"auto_delete\": false,
                             \"vhost\": \"some-vhost\"}]}"
                (core/-main "--filetype" "json" "Foo"))) => (contains "missing/wrong queues for 'orders.hoxton'"))

      (fact "for Bindings"
          (with-fake-http [(no-vhost) "{}"
                           (for-vhost "some-vhost") "{}"
                           {:method :post :url "http://localhost:15672/api/bindings/some-vhost/e/orders/q/orders.hoxton"} {:status 201}]
            (run-with-file "{\"bindings\": [{
                           \"destination\": \"orders.hoxton\",
                             \"destination_type\": \"queue\",
                             \"arguments\": \"{}\",
                             \"routing_key\": \"\",
                             \"source\": \"orders\",
                             \"vhost\": \"some-vhost\"}]}"
                (core/-main "--filetype" "json" "Foo"))) => (contains "missing/wrong bindings for 'orders.hoxton-queue-{}--orders'"))
    ) ; end "Does export-style JSON"

    (fact "Does VHosts"
          (with-fake-http [
                           (no-vhost) "{}"
                      (for-vhost "some-vhost") "{}"
                      {:method :put :url "http://localhost:15672/api/vhosts/some-vhost"} {:status 204}]
        (with-out-str (with-redefs
            [
             core/file-exists (fn [path] true)
             slurp (fn [& _] "{:vhosts [{:name \"some-vhost\"}]}")
             ]
            (core/-main "--filetype" "edn" "Foo")))) => (contains "missing vhost 'some-vhost"))

    (fact "Does Existing VHosts"
          (with-fake-http ["http://localhost:15672/api/vhosts" "[{\"name\":\"some-vhost\"}]"
                           (no-vhost) "{}"
                      (for-vhost "some-vhost") "{}"
                      {:method :put :url "http://localhost:15672/api/vhosts/some-vhost"} {:status 204}]
        (with-out-str (with-redefs
            [
             core/file-exists (fn [path] true)
             slurp (fn [& _] "{:vhosts [{:name \"some-vhost\"}]}")
             ]
            (core/-main "--filetype" "edn" "Foo")))) => "All done\n")

    (fact "Does Permissions"
          (with-fake-http [(no-vhost) "{}"
                           (for-vhost "some-vhost") "{}"
                           {:method :put :url "http://localhost:15672/api/vhosts/some-vhost"} {:status 204}
                           {:method :put :url "http://localhost:15672/api/permissions/some-vhost/chef"} {:status 204}]
        (with-out-str (with-redefs
            [
             core/file-exists (fn [path] true)
             slurp (fn [& _] "{
                      :vhosts
                       [{:name \"some-vhost\"
                         :permissions
                           [{:configure \"\",
                             :write \"\",
                             :user \"chef\",
                             :read \"orders\",
                             }]}]}")
             ]
            (core/-main "--filetype" "edn" "Foo")))) => (contains "missing/wrong permissions for 'chef'"))

    (fact "Does Queues"
        (with-fake-http [(no-vhost) "{}"
                         (for-vhost "some-vhost") "{}"
                         {:method :put :url "http://localhost:15672/api/vhosts/some-vhost"} {:status 204}
                         {:method :put :url "http://localhost:15672/api/queues/some-vhost/orders.hoxton"} {:status 204}]
          (with-out-str (with-redefs
              [
               core/file-exists (fn [path] true)
               slurp (fn [& _] "{
                       :vhosts
                         [{:name \"some-vhost\"
                       :queues
                         [{:name \"orders.hoxton\",
                           :arguments {},
                           :durable true,
                           :auto_delete false}]}]}")
               ]
              (core/-main "--filetype" "edn" "Foo"))) => (contains "missing/wrong queues for 'orders.hoxton'")))

    (fact "Does Exchanges"
        (with-fake-http [(no-vhost) "{}"
                         (for-vhost "some-vhost") "{}"
                         {:method :put :url "http://localhost:15672/api/vhosts/some-vhost"} {:status 204}
                         {:method :put :url "http://localhost:15672/api/exchanges/some-vhost/orders-topic"} {:status 204}]
          (with-out-str (with-redefs
              [
               core/file-exists (fn [path] true)
               slurp (fn [& _] "{
                       :vhosts
                         [{:name \"some-vhost\"
                       :exchanges
                         [{:arguments {},
                           :internal false,
                           :type \"topic\",
                           :name \"orders-topic\",
                           :auto_delete false,
                           :durable true}]}]}")
               ]
              (core/-main "--filetype" "edn" "Foo"))) => (contains "missing/wrong exchanges for 'orders-topic'")))

    (fact "Does Bindings"
        (with-fake-http [(no-vhost) "{}"
                         (for-vhost "some-vhost") "{}"
                         {:method :put :url "http://localhost:15672/api/vhosts/some-vhost"} {:status 204}
                         {:method :post :url "http://localhost:15672/api/bindings/some-vhost/e/orders/q/orders.hoxton"} {:status 201}]
          (with-out-str (with-redefs
              [
               core/file-exists (fn [path] true)
               slurp (fn [& _] "{
                        :vhosts
                         [{:name \"some-vhost\"
                           :bindings
                           [{:destination \"orders.hoxton\",
                           :destination_type \"queue\",
                           :arguments {},
                           :routing_key \"\",
                           :source \"orders\"}]}]}")
               ]
              (core/-main "--filetype" "edn" "Foo"))) => (contains "missing/wrong bindings for 'orders.hoxton-queue-{}--orders'")))
  )
)
