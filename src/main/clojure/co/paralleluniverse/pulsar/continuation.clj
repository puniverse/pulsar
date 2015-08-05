; Pulsar
; Copyright (C) 2013-2015, Parallel Universe Software Co. All rights reserved.
;
; This program and the accompanying materials are dual-licensed under
; either the terms of the Eclipse Public License v1.0 as published by
; the Eclipse Foundation
;
;   or (per the licensee's choosing)
;
; under the terms of the GNU Lesser General Public License version 3.0
; as published by the Free Software Foundation.

(ns co.paralleluniverse.pulsar.continuation
  "Defines scoped continuations"
  (:require [co.paralleluniverse.pulsar.core :refer :all]
            [clojure.core.typed :refer [ann def-alias Option AnyInteger Any U I All IFn HVec]])
  (:import [co.paralleluniverse.pulsar ClojureHelper]
           [co.paralleluniverse.fibers Continuation ValuedContinuation]
           [co.paralleluniverse.continuation ContinuationScope PulsarContinuation]
    ; for types:
           [clojure.lang Keyword IObj IMeta IDeref ISeq IPersistentCollection IPersistentVector IPersistentMap]))


(defmacro cont
  "

  Options:
  :detached b   - whether or not the continuation has its own thread-locals, context classloader etc.
  :stack-size n - the fiber's initial stack size
  "
  {:arglists '([:detached? :stack-size? scope body])}
  [& args]
  (let [[{:keys [^Boolean detached ^Integer stack-size] :or {detached false, stack-size -1}} body] (kps-args args)]
    `(let [scope# ~(keyword (name (first body)))
           f# (suspendable! ~(second body))]
       (co.paralleluniverse.continuation.PulsarContinuation. scope# (boolean ~detached) (int ~stack-size) f#))))

(defn done? [^Continuation cont]
  (.isDone cont))

(defmacro pause
  ([scope]
   `(co.paralleluniverse.continuation.PulsarContinuation/pause ^Keyword ~(keyword (name scope))))
  ;([scope x ccc]
  ; `(co.paralleluniverse.continuation.PulsarContinuation/pause ^Keyword ~(keyword (name scope)) ~x ^IFn ~ccc))
  ([scope x]
   `(co.paralleluniverse.continuation.PulsarContinuation/pause ^Keyword ~(keyword (name scope)) ~x)))

(defmacro suspend [scope ccc]
  "A delimited call-cc"
  `(co.paralleluniverse.continuation.PulsarContinuation/suspend ^Keyword ~(keyword (name scope)) ^IFn ~ccc))

;; lazy-seq

(defn lazy-seq1                                         ; lazy-seq w/o :once
  [f]
  (co.paralleluniverse.pulsar.SuspendableLazySeq. f))

(defsfn cont->lazy-seq [f]                                  ; suspendable b/c of nested continuation
        (let [c (cont LazySeq f)]
          (letsfn [(gen []
                        (let [x (c)]
                          (if (done? c) nil (cons x (lazy-seq1 gen)))))]
                  (gen))))

(defsfn yield [x]
        (pause LazySeq x))

;; ambiguity

(defn- pop! [queue]
  (when (not-empty @queue)
    (loop []
      (let [q @queue
            value (peek q)
            nq (pop q)]
        (if (compare-and-set! queue q nq)
          value
          (recur))))))

(defn- push! [queue x]
  (swap! queue conj x))

(declare ^:dynamic *amb-paths*)

(defn solve0 [f]
  (let [paths (atom (list (cont Ambiguity f)))]
    (fn []
      (binding [*amb-paths* paths]
        (loop []
          (let [^Continuation c (pop! paths)]
            (if c
              (let [res (c)]
                (if (.isDone c)
                  res
                  (recur)))
              (throw (Exception. "No solution")))))))))

(defmacro solve [& body]
  `(solve0 (fn [] ~@body)))

(defsfn amb [& coll]
        (let [values (atom (vec (reverse coll)))]
          (when (< 1 (count @values))
            (loop []
              (when (and (< 1 (count @values))
                         (not (suspend Ambiguity (fn [^PulsarContinuation cont]
                                                   (push! *amb-paths* (.clone cont))
                                                   (cont true)))))
                (recur))))
          (pop! values)))

(defsfn assert [p]
        (when (not p)
          (pause Ambiguity)))

(defsfn solutions [a]                                        ; suspendable b/c of nested continuation
        (cont->lazy-seq #(try
                          (loop []
                            (yield (a))
                            (recur))
                          (catch Exception e
                            (when-not (= (.getMessage e) "No solution")
                              (throw e))))))
