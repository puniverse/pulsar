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

(ns co.paralleluniverse.pulsar.dataflow
  "Dataflow vals and vars"
  (:refer-clojure :exclude [promise await])
  (:require
    [co.paralleluniverse.pulsar.core :refer :all]
    [clojure.core.typed :refer [ann Option AnyInteger I U Any All]])
  (:import
    [co.paralleluniverse.strands.dataflow Val Var]
    [java.util.concurrent TimeUnit TimeoutException]
    ; for types:
    [clojure.lang Seqable LazySeq ISeq]))


(defn df-val
  "Returns a val object that can be read with deref/@, and set,
  once only, by applying the val as a function to the value.
  Calls to deref/@ prior to delivery will block, unless the variant of deref with timeout is used.
  All subsequent derefs will return the same delivered value without
  blocking.

  Attempting to assign a value to the val more than once will result in an IllegalStateException

  See also - realized?."
  ([f]
   (let [v (Val. (->suspendable-callable (suspendable! f)))]
     (sreify
       clojure.lang.IDeref
       (deref [_]
              (.get v))
       clojure.lang.IBlockingDeref
       (deref
         [_ timeout-ms timeout-val]
         (try
           (.get v timeout-ms TimeUnit/MILLISECONDS)
           (catch TimeoutException e
             timeout-val)))
       clojure.lang.IPending
       (isRealized [this]
                   (.isDone v))
       clojure.lang.IFn
       (invoke
         [this x]
         (.set v x)
         this)
       (toString [this] (.toString v)))))
  ([]
   (df-val nil)))

(defn df-var
  "Returns a var, a variable whose value can be set multiple times and by multiple strands, and whose changing values can
  be monitored and propagated.

  The var's value is read with deref/@. That returns the var's current value,
  or, more precisely: it returns the oldest value in the maintained history that has not yet been returned in the calling strand,
  unless this Var does not yet have a value; only in that case will this method block until it is set for the first time.

  The var's value is set by applying the var as a function to the value.
  The var can also be set by providing a formula function, whose return value will set the var's value.
  If the function dereferences other vars (or vals), any change to them will recompute the function and re-set this var's value.

  history - how many historical values to maintain for each strand reading the var
  f - this var's formula function"
  ([^long history f]
   (let [v (Var. (int history) (->suspendable-callable (suspendable! f)))]
     (sreify
       clojure.lang.IDeref
       (deref [_]
              (.get v))
       clojure.lang.IFn
       (invoke
         [this x]
         (try
           (.set v x)
           this
           (catch IllegalStateException _ nil)))
       (toString [this] (.toString v)))))
  ([history-or-f]
   (if (ifn? history-or-f)
     (df-var 0 ^Ifn history-or-f)
     (df-var (int history-or-f) nil)))
  ([]
   (df-var 0 nil)))

