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
(ns co.paralleluniverse.pulsar.async.api_test
  (:use midje.sweet)
  (:refer-clojure :exclude [map into reduce merge take partition partition-by])
  (:require [co.paralleluniverse.pulsar.core :as p])
  (:require [co.paralleluniverse.pulsar.async :refer :all :as a])
  (:import (co.paralleluniverse.strands Strand)
           (co.paralleluniverse.fibers Fiber)))

(defn default-chan []
  (chan 1))

(fact "Buffers"
      (fact "(buffer 1) not unblocking"
            (unblocking-buffer? (buffer 1)) => false)
      (fact "(dropping-buffer 1) unblocking"
            (unblocking-buffer? (dropping-buffer 1)) => true)
      (fact "(sliding-buffer 1) unblocking"
            (unblocking-buffer? (sliding-buffer 1)) => true))

(fact "Basic channel behavior"
      (let [c (default-chan)
            f (future (<!! c))]
      (>!! c 42)
      @f => 42))

(def DEREF_WAIT 20)

(fact "Writes block on full blocking buffer"
  (let [c (default-chan)
        _ (>!! c 42)
        blocking (deref (future (>!! c 43)) DEREF_WAIT :blocked)]
    blocking => :blocked))

(fact "Unfulfilled readers block"
      (let [c (default-chan)
            r1 (future (<!! c))
            r2 (future (<!! c))
            _ (>!! c 42)
            r1v (deref r1 DEREF_WAIT :blocked)
            r2v (deref r2 DEREF_WAIT :blocked)]
        (and (or (= r1v :blocked) (= r2v :blocked))
             (or (= 42 r1v) (= 42 r2v))) => true))

(fact "<!! and put!"
      (let [executed (p/promise)
            test-channel (chan nil)]
        (put! test-channel :test-val (fn [_] (deliver executed true)))
        (fact "The provided callback does not execute until a reader can consume the written value."
              (not (realized? executed)) => true)
        (fact "The written value is provided over the channel when a reader arrives."
              (<!! test-channel) => :test-val)
        (fact "The provided callback executes once the reader has arrived."
              @executed => true)))

(fact "!! and take!"
  (fact "The written value is the value provided to the read callback."
        (let [read-promise (p/promise)
              test-channel (chan nil)]
           (take! test-channel #(deliver read-promise %))
           (fact "The read waits until a writer provides a value."
                 (realized? read-promise) => false)
           (>!! test-channel :test-val)
           (deref read-promise 1000 false)) => :test-val))

(fact "take! on-caller?"
      (fact "When on-caller? requested, but no value is immediately available, take!'s callback executes on another strand."
            (apply not= (let [starting-strand (Strand/currentStrand)
                              test-channel (chan nil)
                              read-promise (p/promise)]
                          (take! test-channel (fn [_] (deliver read-promise (Strand/currentStrand))) true)
                          (>!! test-channel :foo)
                          [starting-strand @read-promise]))
            => true)
      (fact "When on-caller? requested, and a value is ready to read, take!'s callback executes on the same strand."
            (apply = (let [starting-strand (Strand/currentStrand)
                           test-channel (chan nil)
                           read-promise (p/promise)]
                       (put! test-channel :foo (constantly nil))
                       (take! test-channel (fn [_] (deliver read-promise (Strand/currentStrand))) true)
                       [starting-strand @read-promise]))
            => true)
      (fact "When on-caller? is false, and a value is ready to read, take!'s callback executes on a different strand."
            (apply not= (let [starting-strand (Strand/currentStrand)
                              test-channel (chan nil)
                              read-promise (p/promise)]
                          (put! test-channel :foo (constantly nil))
                          (take! test-channel (fn [_] (deliver read-promise (Strand/currentStrand))) false)
                          [starting-strand @read-promise]))
            => true))

(fact "put! on caller?"
      (fact "When on-caller? requested, and a reader can consume the value, put!'s callback executes on the same strand."
            (apply = (let [starting-strand (Strand/currentStrand)
                           test-channel (chan nil)
                           write-promise (p/promise)]
                       (take! test-channel (fn [_] nil))
                       (Strand/sleep 100)
                       (put! test-channel :foo (fn [_] (deliver write-promise (Strand/currentStrand))) true)
                       [starting-strand @write-promise]))
            => true)
      (fact "When on-caller? is false, but a reader can consume the value, put!'s callback executes on a different strand."
            (apply not= (let [starting-strand (Strand/currentStrand)
                              test-channel (chan nil)
                              write-promise (p/promise)]
                          (take! test-channel (fn [_] nil))
                          (put! test-channel :foo (fn [_] (deliver write-promise (Strand/currentStrand))) false)
                          [starting-strand @write-promise]))
            => true)
     (fact "When on-caller? requested, but no reader can consume the value, put!'s callback executes on a different strand."
           (apply not= (let [starting-strand (Strand/currentStrand)
                             test-channel (chan nil)
                             write-promise (p/promise)]
                         (put! test-channel :foo (fn [_] (deliver write-promise (Strand/currentStrand))) true)
                         (take! test-channel (fn [_] nil))
                         [starting-strand @write-promise]))
           => true))

(fact "puts-fulfill-when-buffer-available"
      (= :proceeded
        (let [c (chan 1)
              p (promise)]
          (>!! c :full)  ;; fill up the channel
          (put! c :enqueues (fn [_] (deliver p :proceeded)))  ;; enqueue a put
          (<!! c)        ;; make room in the buffer
          (deref p 250 :timeout))) => true)

(def ^:dynamic test-dyn false)

(fact "thread tests"
      (binding [test-dyn true]
        (fact "bindings"
              (<!! (thread test-dyn))
              => true)))

(fact "fiber tests"
     (binding [test-dyn true]
       (fact "bindings"
             (<!! (fiber test-dyn))
             => true)))
