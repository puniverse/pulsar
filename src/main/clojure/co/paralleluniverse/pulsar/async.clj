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
;
; Docstrings are Copyright (c) Rich Hickey and contributors, Parallel Universe
;
(ns co.paralleluniverse.pulsar.async
  "Implementation of core.async"
  (:import
    [co.paralleluniverse.strands Strand SimpleConditionSynchronizer ConditionSelector]
    [co.paralleluniverse.strands.channels Channel QueueObjectChannel TransferChannel Channels$OverflowPolicy
     SendPort ReceivePort SelectableSend SelectableReceive]
    [co.paralleluniverse.strands.queues ArrayQueue BoxQueue CircularObjectBuffer]
    [co.paralleluniverse.strands.dataflow DelayedVal]
    [co.paralleluniverse.pulsar ClojureHelper]
    [java.util.concurrent TimeUnit ThreadLocalRandom Executors Executor ScheduledExecutorService]
    [com.google.common.util.concurrent ThreadFactoryBuilder])
  (:require
    [co.paralleluniverse.pulsar.core :as p]))

(defn buffer
  "Returns a fixed buffer of size n. When full, puts will block/park."
  [n]
  [(if (= n 1) (BoxQueue. false false) (ArrayQueue. n)) Channels$OverflowPolicy/BLOCK])

(defn dropping-buffer
  "Returns a buffer of size n. When full, puts will complete but val will be dropped (no transfer)."
  [n]
  [(if (= n 1) (BoxQueue. false false) (ArrayQueue. n)) Channels$OverflowPolicy/DROP])

(defn sliding-buffer
  "Returns a buffer of size n. When full, puts will complete, and be buffered, but oldest elements in buffer will be dropped (not transferred)."
  [n]
  [(if (= n 1) (BoxQueue. true false) (CircularObjectBuffer. (int n) false)) Channels$OverflowPolicy/DISPLACE])

(defn chan
  "Creates a channel with an optional buffer. If buf-or-n is a number, will create and use a fixed buffer of that size."
  ([] (chan nil))
  ([buf-or-n] 
   (cond
     (nil? buf-or-n)    (TransferChannel.) ; - TransferChannel doesn't support double-sided alts!. Requires an actual transaction (locks)
     (number? buf-or-n) (chan (buffer buf-or-n))
     :else              (QueueObjectChannel. (first buf-or-n) (second buf-or-n) false))))

(defn <!
  "takes a val from port. Must be called inside a (go ...) block. Will return nil if closed. Will park if nothing is available."
  [port]
  (p/rcv port))

;; Unlike in core.async take! is a secon-class citizen of this implementation. It gives no performance benefits over using go <!
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

(defn >!
  "puts a val into port. nil values are not allowed. Must be called inside a (go ...) block. Will park if no buffer space is available."
  [port val]
  (p/snd port val))

;; Unlike in core.async put! is a secon-class citizen of this implementation. It gives no performance benefits over using go >!
(defn put!
  "Asynchronously puts a val into port, calling fn0 (if supplied) when
  complete. nil values are not allowed. Will throw if closed. If
  on-caller? (default true) is true, and the put is immediately
  accepted, will call fn0 on calling thread.  Returns nil."
([port val] (put! port val nil))
([port val fn0] (put! port val fn0 true))
([port val fn0 on-caller?]
 (if (and on-caller? (p/try-snd port val))
   (when fn0 (fn0))
   (p/spawn-fiber #((p/snd port val) 
                    (when fn0 (fn0)))))))

(defn close!
  "Closes a channel. The channel will no longer accept any puts (they
  will be ignored). Data in the channel remains available for taking, until
  exhausted, after which takes will return nil. If there are any
  pending takes, they will be dispatched with nil. Closing a closed
  channel is a no-op. Returns nil."
[chan]
(p/close! chan))

(defn timeout
  "Returns a channel that will close after msecs"
  [msecs]
  (let [deadline  (long (+ (System/nanoTime) (* msecs 1000000)))
        closed (atom false)
        syn    (SimpleConditionSynchronizer.)
        tim
        (reify 
          ReceivePort
          (isClosed [_] (or @closed (> (System/nanoTime) deadline)))
          (close    [_] 
                 (reset! closed true)
                 (.signalAll syn))
          (receive [this] 
                   (.register syn)
                   (try
                     (loop [i (int 0)]
                       (if (not (.isClosed this))
                         (do 
                           (.await syn i (- deadline (System/nanoTime)) TimeUnit/NANOSECONDS) 
                           (recur (inc i)))
                         (do (.close this)
                           nil)))
                     (finally
                       (.unregister syn))))
          (receive [this ^long t ^TimeUnit unit] 
                   (let [ddlne (long (+ (System/nanoTime) (.toNanos unit t)))]
                     (.register syn)
                     (try
                       (loop [i (int 0)
                              left (long (.toNanos unit t))]
                         (if (and (> left 0) (not (.isClosed this)))
                           (do 
                             (.await syn i (min left (- deadline (System/nanoTime))) TimeUnit/NANOSECONDS) 
                             (recur (inc i) (- ddlne (System/nanoTime))))
                           (when (.isClosed this)
                             (.close this)
                             nil)))
                       (finally
                         (.unregister syn)))))
          (tryReceive [_] nil)
          SelectableReceive
          (receiveSelector [_] syn))]
    (ClojureHelper/schedule #(.close tim) (long msecs) TimeUnit/MILLISECONDS)
    tim))

;; This function is copied from core.async, copyright Rich Hicky and contributors.
(defn random-array
  [n]
  (let [rand (ThreadLocalRandom/current)
        a (int-array n)]
    (loop [i 1]
      (if (== i n)
        a
        (do
          (let [j (.nextInt rand (inc i))]
            (aset a i (aget a j))
            (aset a j i)
            (recur (inc i))))))))

(defn try-receive0 [^ReceivePort port]
  (let [m (.tryReceive port)]
    (if (and (nil? m) (.isClosed port))
      ::closed
      m)))

(defn try-ports [ports priority]
  (let [n (count ports)
        ^ints ra (when (not priority) (random-array n))]
    (loop [i (int 0)]
      (when (< i n)
        (let [p (nth ports (if ra (aget ra i) i))]
          (or 
            (if (vector? p)
              (when (.trySend ^co.paralleluniverse.strands.channels.SendPort (first p) (second p))
                [nil p])
              (when-let [m (try-receive0 p)]
                [(if (= m ::closed) nil m) p]))
            (recur (inc i))))))))

(defmacro alts!
  "Completes at most one of several channel operations. Must be called
  inside a (go ...) block. ports is a set of channel endpoints, which
  can be either a channel to take from or a vector of
  [channel-to-put-to val-to-put], in any combination. Takes will be
  made as if by <!, and puts will be made as if by >!. Unless
  the :priority option is true, if more than one port operation is
  ready a non-deterministic choice will be made. If no operation is
  ready and a :default value is supplied, [default-val :default] will
  be returned, otherwise alts! will park until the first operation to
  become ready completes. Returns [val port] of the completed
  operation, where val is the value taken for takes, and nil for puts.
  
  opts are passed as :key val ... Supported options:
  
  :default val - the value to use if none of the operations are immediately ready
  :priority true - (default nil) when true, the operations will be tried in order.
  
  Note: there is no guarantee that the port exps or val exprs will be
  used, nor in what order should they be, so they should not be
  depended upon for side effects."

[ports & {:as opts}]
(let [priority  (:priority opts)]
  (if (contains? opts :default)
    `(if-let [res# (try-ports ~ports ~priority)]
       res#
       [~(:default opts) :default])
    `(let [ps# ~ports]
       (if-let [res# (try-ports ps# ~priority)] 
         res#
         (let [^co.paralleluniverse.strands.Condition sel# 
               (co.paralleluniverse.strands.ConditionSelector. 
                 ^java.util.Collection
                 (map #(if (vector? %) 
                         (.sendSelector    ^co.paralleluniverse.strands.channels.SelectableSend    (first %))
                         (.receiveSelector ^co.paralleluniverse.strands.channels.SelectableReceive %))
                      ps#))] ; no default
           (.register sel#)
           (try
             (loop [i# (int 0)] ; retry loop
               (if-let [res# (try-ports ps# ~priority)] 
                 res#
                 (do 
                   (.await sel# i#)
                   (recur (inc i#)))))
             (finally
               (.unregister sel#)))))))))

;; This function is copied from core.async, copyright Rich Hicky and contributors.
(defn do-alt [alts clauses]
  (assert (even? (count clauses)) "unbalanced clauses")
  (let [clauses (partition 2 clauses)
        opt? #(keyword? (first %)) 
        opts (filter opt? clauses)
        clauses (remove opt? clauses)
        [clauses bindings]
        (reduce
          (fn [[clauses bindings] [ports expr]]
            (let [ports (if (vector? ports) ports [ports])
                  [ports bindings]
                  (reduce
                    (fn [[ports bindings] port]
                      (if (vector? port)
                        (let [[port val] port
                              gp (gensym)
                              gv (gensym)]
                          [(conj ports [gp gv]) (conj bindings [gp port] [gv val])])
                        (let [gp (gensym)]
                          [(conj ports gp) (conj bindings [gp port])])))
                    [[] bindings] ports)]
              [(conj clauses [ports expr]) bindings]))
          [[] []] clauses)
        gch (gensym "ch")
        gret (gensym "ret")]
    `(let [~@(mapcat identity bindings)
           [val# ~gch :as ~gret] (~alts [~@(apply concat (map first clauses))] ~@(apply concat opts))]
       (cond
         ~@(mapcat (fn [[ports expr]]
                     [`(or ~@(map (fn [port]
                                    `(= ~gch ~(if (vector? port) (first port) port)))
                                  ports))
                      (if (and (seq? expr) (vector? (first expr)))
                        `(let [~(first expr) ~gret] ~@(rest expr)) 
                        expr)])
                   clauses)
         (= ~gch :default) val#))))

(defmacro alt!
  "Makes a single choice between one of several channel operations,
  as if by alts!, returning the value of the result expr corresponding
  to the operation completed. Must be called inside a (go ...) block.
  
  Each clause takes the form of:
  
  channel-op[s] result-expr
  
  where channel-ops is one of:
  
  take-port - a single port to take
  [take-port | [put-port put-val] ...] - a vector of ports as per alts!
  :default | :priority - an option for alts!
  
  and result-expr is either a list beginning with a vector, whereupon that
  vector will be treated as a binding for the [val port] return of the
  operation, else any other expression.
  
  (alt!
  [c t] ([val ch] (foo ch val))
  x ([v] v)
  [[out val]] :wrote
  :default 42)
  
  Each option may appear at most once. The choice and parking
  characteristics are those of alts!."
[& clauses]
(do-alt `alts! clauses))

(defn- f->chan
  [c f]
  (p/susfn []
    (let [ret (try (f)
                (catch Throwable t
                  nil))]
      (when-not (nil? ret)
        (>! c ret))
      (close! c))))

(defonce ^:private ^Executor thread-macro-executor
 (Executors/newCachedThreadPool (-> (ThreadFactoryBuilder.) (.setNameFormat "async-thread-%d") (.setDaemon true) (.build))))

(defn thread-call
  "Executes f in another thread, returning immediately to the calling
  thread. Returns a channel which will receive the result of calling
  f when completed."
[f]
(let [c (chan 1)]
  (.execute thread-macro-executor (f->chan c f))
  c))

(defmacro thread
  "Executes the body in another thread, returning immediately to the
  calling thread. Returns a channel which will receive the result of
  the body when completed."
[& body]
`(thread-call (fn [] ~@body)))

;; This function is not part of core.async. It is provided here for symmetry with thread-call
(defn fiber-call
  [f]
  (let [c (chan 1)]
    (p/spawn-fiber (f->chan c (p/suspendable! f)))
    c))

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
`(fiber-call (fn [] ~@body)))


;; The following defs are redundant in this implementation, but are provided for compatibility with core.async

(defn <!!
  "takes a val from port. Will return nil if closed. Will block if nothing is available."
  [port]
  (<! port))

(defn >!!
  "puts a val into port. nil values are not allowed. Will block if no buffer space is available. Returns nil."
  [port val]
  (>! port val))

(defmacro alts!!
  [& args]
  `(alts! ~@args))

(defmacro alt!!
  [& args]
  `(alt! ~@args))
