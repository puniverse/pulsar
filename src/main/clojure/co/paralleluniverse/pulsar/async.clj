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

(ns co.paralleluniverse.pulsar.async
  "Implementation of core.async"
  (:import
    [co.paralleluniverse.strands Strand]
    [co.paralleluniverse.strands.channels Channel QueueObjectChannel TransferChannel Channels$OverflowPolicy]
    [co.paralleluniverse.strands.queues ArrayQueue CircularObjectBuffer]
    [co.paralleluniverse.strands.dataflow DelayedVal])
  (:require
    [co.paralleluniverse.pulsar.core :as p]))

(defn buffer
  "Returns a fixed buffer of size n. When full, puts will block/park."
  [n]
  [(ArrayQueue. n) Channels$OverflowPolicy/BLOCK])

(defn dropping-buffer
  "Returns a buffer of size n. When full, puts will complete but val will be dropped (no transfer)."
  [n]
  [(ArrayQueue. n) Channels$OverflowPolicy/DROP])

(defn sliding-buffer
  "Returns a buffer of size n. When full, puts will complete, and be buffered, but oldest elements in buffer will be dropped (not transferred)."
  [n]
  [(CircularObjectBuffer. (int n) false) Channels$OverflowPolicy/DISPLACE])

(defn chan
  "Creates a channel with an optional buffer. If buf-or-n is a number, will create and use a fixed buffer of that size."
  ([] (chan nil))
  ([buf-or-n] 
   (cond
     (nil? buf-or-n)    (TransferChannel.)
     (number? buf-or-n) (QueueObjectChannel. (ArrayQueue. buf-or-n) Channels$OverflowPolicy/BLOCK false)
     :else              (QueueObjectChannel. (first buf-or-n) (second buf-or-n) false))))

(defn timeout
  "Returns a channel that will close after msecs"
  [msecs]
  (let [ch (chan)]
    (p/spawn-fiber #((Strand/sleep msecs) 
                     (p/close! ch)))
    ch))

(defn <!!
  "takes a val from port. Will return nil if closed. Will block if nothing is available."
  [port]
  (p/rcv port))

(defn <!
  "takes a val from port. Must be called inside a (go ...) block. Will return nil if closed. Will park if nothing is available."
  [port]
  (p/rcv port))

(defn take!
  "Asynchronously takes a val from port, passing to fn1. Will pass nil
  if closed. If on-caller? (default true) is true, and value is
  immediately available, will call fn1 on calling thread.
  Returns nil."
([port fn1] (take! port fn1 true))
([port fn1 on-caller?]
 (if-let [v (and on-caller? (p/try-rcv port))]
   (fn1 v)
   (p/spawn-fiber #(fn1 (p/rcv port))))))

(defn >!!
  "puts a val into port. nil values are not allowed. Will block if no buffer space is available. Returns nil."
  [port val]
  (p/snd port val))

(defn >!
  "puts a val into port. nil values are not allowed. Must be called inside a (go ...) block. Will park if no buffer space is available."
  [port val]
  (p/snd port val))

(defn put!
  "Asynchronously puts a val into port, calling fn0 when complete. nil
  values are not allowed. Will throw if closed. If
  on-caller? (default true) is true, and the put is immediately
  accepted, will call fn0 on calling thread.  Returns nil."
([port val fn0] (put! port val fn0 true))
([port val fn0 on-caller?]
 (if (and on-caller? (p/try-snd port val))
   (fn0)
   (p/spawn-fiber #((p/snd port val) 
                    (fn0))))))

(defn close!
  "Closes a channel. The channel will no longer accept any puts (they
  will be ignored). Data in the channel remains available for taking, until
  exhausted, after which takes will return nil. If there are any
  pending takes, they will be dispatched with nil. Closing a closed
  channel is a no-op. Returns nil."
[chan]
(p/close! chan))

(defn thread-call
  "Executes f in another thread, returning immediately to the calling
  thread. Returns a channel which will receive the result of calling
  f when completed."
[f]
(let [ch (chan)]
  (p/spawn-fiber (fn [] 
                   (try
                     (let [res (f)]
                       (p/snd ch res))
                     (finally
                       (p/close! ch)))))
  ch))

(defmacro go
  "Asynchronously executes the body, returning immediately to the
  calling thread. Additionally, any visible calls to <!, >! and alt!/alts!
  channel operations within the body will block (if necessary) by
  'parking' the calling thread rather than tying up an OS thread (or
  the only JS thread when in ClojureScript). Upon completion of the
  operation, the body will be resumed.
  
  Returns a channel which will receive the result of the body when
  completed"
[& body]
`(thread-call (fn [] ~@body)))

(defmacro thread
  "Executes the body in another thread, returning immediately to the
  calling thread. Returns a channel which will receive the result of
  the body when completed."
[& body]
`(thread-call (fn [] ~@body)))
