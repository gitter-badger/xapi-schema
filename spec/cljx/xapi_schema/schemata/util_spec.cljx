(ns xapi-schema.schemata.util-spec
  #+cljs (:require-macros [speclj.core :refer [describe
                                               context
                                               with
                                               it
                                               should
                                               should=
                                               should-not
                                               run-specs
                                               should-not-throw]])
  #+cljs (:require [speclj.core]
                   [xapi-schema.schemata.util :refer [check-type
                                                      object-type-dispatch
                                                      error->string
                                                      errors->data
                                                      named-error?
                                                      validation-error?]]
                   [xapi-schema.schemata.json :as json]
                   [schema.core :as s
                    :include-macros true]
                   [schema.utils :as su]
                   [clojure.walk :refer [postwalk]]
                   [xapi-schema.support.data :as d])
  #+clj (:require [speclj.core :refer :all]
                  [schema.core :as s]
                  [schema.utils :as su]
                  [xapi-schema.schemata.util :refer :all]
                  [xapi-schema.schemata.json :as json]
                  [clojure.walk :refer [postwalk]]
                  [xapi-schema.support.data :as d]))

(describe "check-type"
          (with pred (check-type "Activity"))
          (it "returns a predicate that checks for the given objectType"
              (should (@pred {"objectType" "Activity"}))
              (should-not (@pred {"objectType" "Agent"}))))

(describe "object-type-dispatch"
          (with foo-schema
                {(s/required-key "objectType") s/Str
                 (s/required-key "thing") s/Str})
          (with bar-schema
                {(s/required-key "objectType") s/Str
                 (s/required-key "thing") s/Num})
          (with baz-schema {(s/required-key "thing") s/Keyword})


          (with foo {"objectType" "foo"
                     "thing" "whatever"})
          (with bar {"objectType" "bar"
                     "thing" 2})
          (with baz {"thing" :whatever})

          (with conditional-schema (object-type-dispatch
                                    "foo" @foo-schema
                                    "bar" @bar-schema
                                    :else @baz-schema))
          (it "returns a conditional schema for the given map of object types"
              (should-not (or (s/check @conditional-schema
                                       @foo)
                              (s/check @conditional-schema
                                       @bar)
                              (s/check @conditional-schema
                                       @baz)))
              (should (and
                       (s/check @conditional-schema {"objectType" "foo"
                                                     "thing" :whatever})
                       (s/check @conditional-schema {"thing" "foo"})))))

(describe
 "error fns"
 (describe
  "error->string"
  (context
   "given a missing key error"
   (with err (s/check {(s/required-key "foo") s/Str} {}))
   (it "converts it to an English string"
       (should= "Missing"
                (error->string  (get
                                 @err
                                 "foo")))))
  (context
   "given a disallowed key error"
   (with err (s/check {} {"foo" "bar"}))
   (it "converts it to an English string"
       (should= "Not Allowed"
                (error->string (get
                                @err
                                "foo")))))
  (context
   "given a NOT validation error"
   (context
    "from a predicate"
    (with err (su/validation-error-explain ;; expect it to be coerced!
               (s/check (s/pred (fn [bar]
                                  (= "bar" bar)) "a bar") "foo")))
    (it "converts it to an English string"
        (should= "Not a bar: foo"
                 (error->string @err))))
   (context
    "from a predicate that throws"
    (with err (su/validation-error-explain
               (s/check (s/pred seq "a sequable thing") true)))
    (it "mentions it"
        (should= "Not a sequable thing: true (threw)"
                 (error->string @err))))
   (context
    "from a seq schema"
    (with err (su/validation-error-explain
               (s/check [] {})))
    (it "converts"
        (should= "Not sequential: {}"
                 (error->string @err))))
   (context
    "from a map schema"
    (with err (su/validation-error-explain
               (s/check {} [])))
    (it "converts"
        (should= "Not map: []"
                 (error->string @err))))
   (context
    "from an integer"
    (with err (su/validation-error-explain
               (s/check s/Int "foo")))
    (it "converts"
        (should= "Not an integer: foo"
                 (error->string @err))))
   (context
    "from s/Str"
    (with err (su/validation-error-explain
               (s/check s/Str 1)))
    (it "converts"
        (should= "Not a string: 1"
                 (error->string @err))))
   (context
    "from s/Num"
    (with err (su/validation-error-explain
               (s/check s/Num "foo")))
    (it "converts"
        (should= "Not a number: foo"
                 (error->string @err))))
   (context
    "from s/Bool"
    (with err (su/validation-error-explain
               (s/check s/Bool "foo")))
    (it "converts"
        (should= "Not a boolean: foo"
                 (error->string @err))))))

 (describe
  "named-error?"
  (it "returns true if the error is a named error"
      (should (named-error? (s/check (s/named s/Str "foo") 1)))))

 (describe
  "validation-error?"
  (it "returns true if the error is a validation error"
      (should (validation-error? (s/check s/Str 1)))))

 (describe
  "errors->data"
  (context
   "with nested named and validation errors"
   (with schema (s/named
                 {(s/required-key "foo") s/Str
                  (s/required-key "bar") s/Num
                  (s/required-key "baz") s/Int
                  (s/required-key "quxx") s/Bool
                  (s/required-key "map") {}
                  (s/required-key "string-seq") [s/Str]
                  (s/required-key "not-there") s/Any
                  (s/required-key "equals") (s/eq "foo")
                  (s/required-key "enum") (s/enum "foo" "bar" "baz")
                  (s/required-key "one") [(s/one s/Str "at least one string")]}
                 "bob"))
   (with err (s/check @schema {"foo" 1
                               "bar" true
                               "baz" 1.1
                               "quxx" "foo"
                               "map" []
                               "string-seq" {}
                               "unknown-key" "hey"
                               "equals" "bar"
                               "enum" "quxx"
                               "one" []
                               }))
   (it "converts all error objects to data"
       (should-not-throw
        (postwalk
         (fn [node]
           (if (or (named-error? node)
                   (validation-error? node))
             (throw (#+clj Exception.
                    #+cljs js/Error. "error obj found!"))
             node))
         (errors->data @err))))
   (it "converts all predicate and scalar errors to strings"
       (should= {"unknown-key" "Not Allowed"
                 "not-there" "Missing"
                 "string-seq" "Not sequential: {}"
                 "map" "Not map: []"
                 "quxx" "Not a boolean: foo"
                 "baz" "Not an integer: 1.1"
                 "bar" "Not a number: true"
                 "foo" "Not a string: 1"
                 "equals" "Not foo: bar"
                 "enum" "Not in #{\"foo\" \"bar\" \"baz\"}: quxx"
                 "one" ["Not present: at least one string"]}
                (errors->data @err))))
  (context "given some xapi validation cases"
           (it "parses an agent objectType error"
               (should-not-throw
                (errors->data
                 (s/check json/Statement
                          (assoc d/long-statement
                                 "actor"
                                 {"mbox" "mailto:milt@yetanalytics.com"
                                  "objectType" "NotAnAgent"}))))))))
