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

(ns co.paralleluniverse.pulsar.dataflow-test
  (:refer-clojure :exclude [promise await])
  (:use midje.sweet
        co.paralleluniverse.pulsar.core)
  (:require [co.paralleluniverse.pulsar.dataflow :refer :all]
            [midje.checking.core :as checking]))

(facts "vals"
       (fact "When try to set val twice, then throw exception"
             (let [v (df-val)]
               (v "hi!")
               (fact (v "bye!") => (throws IllegalStateException))
               @v => "hi!"))
       (fact "This complex val test passes"
             (let [v1 (df-val)
                   v2 (df-val)
                   v3 (df-val)
                   v4 (df-val)
                   f1 (spawn-fiber  #(v2 (+ @v1 1)))
                   t1 (spawn-thread #(v3 (+ @v1 @v2)))
                   f2 (spawn-fiber  #(v4 (+ @v3 @v2)))]
               (sleep 50)
               (v1 1)
               (join [f1 f2 t1])
               (fact
                 (mapv realized? [v1 v2 v3 v4]) => [true true true true])
               @v4) => 5)
       (fact "Test val functions"
             (let [v0 (df-val)
                   v1 (df-val)
                   v2 (df-val #(+ @v1 1))
                   v3 (df-val #(+ @v1 @v2))
                   v4 (df-val #(* (+ @v3 @v2) @v0))]
               (sleep 50)
               (v1 1)
               (fact @v3 => 3)
               (fact
                 (mapv realized? [v0 v1 v2 v3 v4]) => [false true true true false])
               (v0 2)
               @v4) => 10))

(facts "vars"
       (fact "Block until var is set for the first time"
             (let [x (df-var)

                   f (fiber
                       (sleep 50)
                       (x 45))]
               @x) => 45)
       (fact "When no history, return last"
             (let [x (df-var)]
               (x 1)
               (x 2)
               (x 3)
               @x) => 3)
       (fact "When history, return history"
             (let [x (df-var 10)]
               (x 1)
               (x 2)
               (x 3)
               (fact @x => 1)
               (fact @x => 2)
               (fact @x => 3))))

(fact "vals and vars"
      (let [res (atom [])
            a (df-val)
            x (df-var)
            y (df-var #(* @a @x))
            z (df-var #(+ @a @x))
            r (df-var #(let [v (+ @a @y @z)]
                        (swap! res conj v)))
            f (fiber
                (loop [i 0]
                  (when (< i 5)
                    (sleep 50)
                    (x i)
                    (recur (inc i)))))]
        (sleep 10)
        (a 3)
        (join f)
        (sleep 100)
        (last @r)) => 22)