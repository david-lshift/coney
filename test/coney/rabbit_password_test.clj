(ns coney.rabbit-password-test
  (:require [coney.rabbit-password :as rp])
  (:use [midje.sweet]))

(facts "password check works"
       (fact "Password checking works for good hash"
             (rp/check-rabbit-password "customer" "iP6qNmNiOAXaD6W53xfm6xCrCNQ=") => true)
       (fact "Password checking works for bad hash"
             (rp/check-rabbit-password "customer" "aaaaaaaaaaaaaaaaaaaaaaaaaaaa") => false)
       (fact "Password checking works for empty hash"
             (rp/check-rabbit-password "customer" "") => false)
       (fact "Password checking works for null hash"
             (rp/check-rabbit-password "customer" nil) => false)
       (fact "Password checking works for null password"
             (rp/check-rabbit-password nil "foo") => false))
