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


;; ambiguity

(facts
  (fact
    (let [a1 (solve
               (let [a (amb 1 2 3 4)]
                 a))]
      (fact (a1) => 1)
      (fact (a1) => 2)
      (fact (a1) => 3)
      (fact (a1) => 4)
      (fact (a1) => (throws Exception))))
  (fact
    (let [a1 (solve
               (let [a (amb 1 2 3 4)]
                 a))]
      (solutions a1)) => '(1 2 3 4))
  (fact
    (let [a1 (solve
               (let [a (amb 1 2)
                     b (amb "a" "b")]
                 (str b a)))]
      (solutions a1)) => '("a1" "b1" "a2" "b2"))
  (fact
    (let [a1 (solve
               (let [a (amb 1 2 3)
                     b (amb 2 3 4)]
                 (assert (> a b))
                 b))]
      (solutions a1)) => '(2))
  (fact
    (let [a1 (solve
               (let [a (amb 1 2 3)
                     b (amb 3 4)]
                 (assert (> a b))
                 b))]
      (solutions a1)) => nil)
  (fact
    (let [a1 (solve
               (let [a (cont->lazy-seq (sfn []
                                            (yield (amb 2 1))
                                            (yield (amb 3 10))))]
                 (loop [xs a
                        sum 0]
                   (if (seq? xs)
                     (let [x (first xs)]
                       (assert (even? x))
                       (recur (next xs) (+ sum x)))
                     (do sum)))))]
      (solutions a1)) => '(12)))







