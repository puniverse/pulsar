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

(ns co.paralleluniverse.pulsar.dataflow
  "Defines a promise fn that's compatible with pulsar fibers"
  (:import [java.util.concurrent TimeUnit ExecutionException TimeoutException]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.strands.dataflow DelayedVal]
           ; for types:
           [clojure.lang Keyword IObj IFn IMeta IDeref ISeq IPersistentCollection IPersistentVector IPersistentMap])
  (:require [co.paralleluniverse.pulsar.core :refer :all]
            [clojure.core.typed :refer [ann def-alias Option AnyInteger]]))

(defn promise
  "Returns a promise object that can be read with deref/@, and set,
  once only, with deliver. Calls to deref/@ prior to delivery will
  block, unless the variant of deref with timeout is used. All
  subsequent derefs will return the same delivered value without
  blocking. See also - realized?.

  Unlike clojure.core/promise, this promise object can be used inside Pulsar fibers."
  ([]
   (let [dv (DelayedVal.)]
     (reify
       clojure.lang.IDeref
       (deref [_]
              (.get dv))
       clojure.lang.IBlockingDeref
       (deref
        [_ timeout-ms timeout-val]
        (try
          (.get dv timeout-ms TimeUnit/MILLISECONDS)
          (catch TimeoutException e
            timeout-val)))
       clojure.lang.IPending
       (isRealized [this]
                   (.isDone dv))
       clojure.lang.IFn
       (invoke
        [this x]
        (.set dv x)
        this))))
  ([f]
   (let [p (promise)]
     (suspendable! f)
     (spawn-fiber #(deliver p (f)))
     p)))