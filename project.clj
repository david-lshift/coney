(defproject coney "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "https://github.com/lshift/coney"
  :license {:name "Mozilla Public License Version 1.1"
            :url "https://www.mozilla.org/MPL/1.1/"}
  :dependencies [
                 [org.clojure/clojure "1.6.0"]
                 [org.clojure/data.codec "0.1.0"]
                 [digest "1.4.4"]
                 [http-kit "2.1.16"]
                 [cheshire "5.4.0"]
                 ]
  :main ^:skip-aot coney.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})