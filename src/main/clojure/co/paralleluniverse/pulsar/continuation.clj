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
           f# (suspendable! (fn [] ~@(rest body)))]
       (co.paralleluniverse.continuation.PulsarContinuation. scope# (boolean ~detached) (int ~stack-size) f#))))

(defn done? [^Continuation cont]
  (.isDone cont))

(defmacro pause
  ([scope]
   `(co.paralleluniverse.continuation.PulsarContinuation/pause ^Keyword ~(keyword (name scope))))
  ([scope x ccc]
   `(co.paralleluniverse.continuation.PulsarContinuation/pause ^Keyword ~(keyword (name scope)) ~x ^IFn ccc))
  ([scope x]
   `(co.paralleluniverse.continuation.PulsarContinuation/pause ^Keyword ~(keyword (name scope)) ~x)))

(defmacro suspend [scope ccc]
  `(co.paralleluniverse.continuation.PulsarContinuation/suspend ^Keyword ~(keyword (name scope)) ^IFn ccc))

;; lazy-seq

(defn cont->lazy-seq [f]
  (let [c (cont LazySeq ((suspendable! f)))]
    (letfn [(gen []
                 (let [x (c)]
                   (if (done? c) nil (cons x (lazy-seq (gen))))))]
      (gen))))

(defsfn yield [x]
  (pause LazySeq x))
