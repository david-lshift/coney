(ns coney.rabbit-password-test
  (:require [coney.rabbit-password :as rp])
  (:use [midje.sweet])
)

(facts "password check works"
  (fact "Password checking works for good hash"
    (rp/check-rabbit-password "customer" "iP6qNmNiOAXaD6W53xfm6xCrCNQ=") => true)
  (fact "Password checking works for bad hash"
    (rp/check-rabbit-password "customer" "aaaaaaaaaaaaaaaaaaaaaaaaaaaa") => false)
  (fact "Password checking works for empty hash"
    (rp/check-rabbit-password "customer" "") => false)
)
