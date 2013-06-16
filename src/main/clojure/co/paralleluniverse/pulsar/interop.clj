; Pulsar: lightweight threads and Erlang-like actors for Clojure.
; Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
;
; This program and the accompanying materials are dual-licensed under
; either the terms of the Eclipse Public License v1.0 as published by
; the Eclipse Foundation
;
;   or (per the licensee's choosing)
;
; under the terms of the GNU Lesser General Public License version 3.0
; as published by the Free Software Foundation.

(ns co.paralleluniverse.pulsar.interop
  "Quasar-Pulsar interop helpers"
  (:import
    ; for types:
    [clojure.lang Seqable LazySeq ISeq])
  (:require
    [clojure.string :as str]
    [clojure.core.typed :refer [ann Option AnyInteger]]))


(ann camel-to-dash [String -> String])
(defn camel-to-dash ; there are many ways of doing this, but it doesn't have to be fast
  [^String s]
  (.toLowerCase (str/replace s #"[a-z0-9][A-Z]" #(str (first %) \- (second %)))))
;  (apply str (cons (Character/toLowerCase (first s))
;                   (map #(if (Character/isUpperCase %) (str "-" (Character/toLowerCase %)) %) (next s)))))

(ann underscore-to-dash [String -> String])
(defn underscore-to-dash
  [^String s]
  (.toLowerCase (.replace s \_ \-)))

(ann java-name->clojure [String -> String])
(defn java-name->clojure
  [^String s]
  (-> s camel-to-dash underscore-to-dash))

;; See http://cemerick.com/2010/08/03/enhancing-clojures-case-to-evaluate-dispatch-values/
(defmacro case+
  "Same as case, but evaluates dispatch values, needed for referring to
  class and def'ed constants as well as java.util.Enum instances."
  [value & clauses]
  (let [clauses (partition 2 2 nil clauses)
        default (when (-> clauses last count (== 1))
                  (last clauses))
        clauses (if default (drop-last clauses) clauses)
        eval-dispatch (fn [d]
                        (if (list? d)
                          (map eval d)
                          (eval d)))]
    `(case ~value
       ~@(concat (->> clauses
                      (map #(-> % first eval-dispatch (list (second %))))
                      (mapcat identity))
                 default))))

(defmacro enum->keyword
  [enum-type x]
  `(when ~x
     (case (.ordinal ^Enum ~x) ; see http://stackoverflow.com/questions/16777814/is-it-possible-to-use-clojures-case-form-with-a-java-enum/16778119
       ~@(mapcat #(list (.ordinal %) (keyword (java-name->clojure (.name %))))
                 (eval (list '. enum-type 'values))))))

#_(defmacro enum->keyword
    [enum-type x]
    `(when ~x
       (case+ ~x
              ~@(mapcat #(list (symbol (str enum-type "/" (.name %))) (keyword (java-name->clojure (.name %))))
                        (eval (list '. enum-type 'values))))))

(defmacro keyword->enum
  [enum-type x]
  `(when ~x
     (case ~x
       ~@(mapcat #(list (keyword (java-name->clojure (.name %))) (symbol (str enum-type "/" (.name %))))
                 (eval (list '. enum-type 'values))))))
