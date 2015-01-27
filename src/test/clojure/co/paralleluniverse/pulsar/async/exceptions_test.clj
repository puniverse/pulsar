; Pulsar: lightweight threads and Erlang-like actors for Clojure.
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
;
;
; Tests are derived from core.async (https://github.com/clojure/core.async).
; Copyright (C) 2013 Rich Hickey and contributors.
; Distributed under the Eclipse Public License, the same as Clojure.
;

(ns co.paralleluniverse.pulsar.async.exceptions-test
  "Verify that exceptions thrown on a thread pool managed by
  core.async will propagate out to the JVM's default uncaught
  exception handler."
  (:use midje.sweet)
  (:require
    [clojure.stacktrace :refer [root-cause]]
    [co.paralleluniverse.pulsar.core :as p]
    [co.paralleluniverse.pulsar.async :refer [chan go thread put! take! <!! >!!]])
  (:import (co.paralleluniverse.fibers Fiber)
           (co.paralleluniverse.strands Strand$UncaughtExceptionHandler)))

(defn with-default-uncaught-exception-handler-fiber [handler f]
  (let [old-handler-fiber (Fiber/getDefaultUncaughtExceptionHandler)]
    (try
      (Fiber/setDefaultUncaughtExceptionHandler
        (reify Strand$UncaughtExceptionHandler
          (uncaughtException [_ strand throwable]
            (handler strand throwable))))
      (f)
      (Fiber/setDefaultUncaughtExceptionHandler old-handler-fiber)
      (catch Throwable t (.printStackTrace t) (throw t)))))

(defn with-default-uncaught-exception-handler-thread [handler f]
  (let [old-handler-thread (Thread/getDefaultUncaughtExceptionHandler)]
    (try
      (Thread/setDefaultUncaughtExceptionHandler
        (reify Thread$UncaughtExceptionHandler
          (uncaughtException [_ thread throwable]
            (handler thread throwable))))
      (f)
      (Thread/setDefaultUncaughtExceptionHandler old-handler-thread)
      (catch Throwable t (.printStackTrace t) (throw t)))))

(fact "Exception in thread"
      (let [log (p/promise)
            ex (Exception. "This exception is expected")]
        (with-default-uncaught-exception-handler-thread
          (fn [_ throwable] (deliver log throwable))
          #(let [ret (thread (throw ex))]
            (<!! ret)))
        (fact (identical? ex (root-cause @log)) => true)))

(fact "Exception in go"
  (let [log (p/promise)]
    (with-default-uncaught-exception-handler-fiber
      (p/sfn [_ throwable] (deliver log throwable))
      #(let [ex (Exception. "This exception is expected")
             ret (go (throw ex))]
        (<!! ret)
        (fact (root-cause @log) => ex)))))

(fact "Exception in put callback"
  (let [log (p/promise)]
    (with-default-uncaught-exception-handler-fiber
      (p/sfn [_ throwable] (deliver log throwable))
      #(let [ex (Exception. "This exception is expected")
             c (chan)]
        (put! c :foo (fn [_] (throw ex)))
        (<!! c)
        (fact (root-cause @log) => ex)))))

(fact "Exception in take callback"
  (let [log (p/promise)]
    (with-default-uncaught-exception-handler-fiber
      (p/sfn [_ throwable] (deliver log throwable))
      #(let [ex (Exception. "This exception is expected")
             c (chan)]
        (take! c (fn [_] (throw ex)))
        (>!! c :foo)
        (fact (root-cause @log) => ex)))))
