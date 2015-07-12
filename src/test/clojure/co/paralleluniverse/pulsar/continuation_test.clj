; Pulsar: lightweight threads and Erlang-like actors for Clojure.
; Copyright (C) 2013-2014, Parallel Universe Software Co. All rights reserved.
;
; This program and the accompanying materials are dual-licensed under
; either the terms of the Eclipse Public License v1.0 as published by
; the Eclipse Foundation
;
;   or (per the licensee's choosing)
;
; under the terms of the GNU Lesser General Public License version 3.0
; as published by the Free Software Foundation.

(ns co.paralleluniverse.pulsar.continuation-test
  (:use midje.sweet
        co.paralleluniverse.pulsar.core
        co.paralleluniverse.pulsar.continuation))

;; ## lazy-seq

(defsfn range [a b]
        (when (< a b)
          (yield a)
          (recur (inc a) b)))

(fact "generate a lazy sequence using a continuation"
      (cont->lazy-seq #(range 1 5))  => '(1 2 3 4))


