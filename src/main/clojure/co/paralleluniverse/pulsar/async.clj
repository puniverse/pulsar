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
    [co.paralleluniverse.strands.channels Channel QueueObjectChannel TransferChannel TimeoutChannel Channels$OverflowPolicy
     SendPort ReceivePort Selectable Selector SelectAction]
    [co.paralleluniverse.strands.queues ArrayQueue BoxQueue CircularObjectBuffer]
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
  "Returns a buffer of size n. When full, puts will complete but 
  val will be dropped (no transfer)."
  [n]
  [(if (= n 1) (BoxQueue. false false) (ArrayQueue. n)) Channels$OverflowPolicy/DROP])

(defn sliding-buffer
  "Returns a buffer of size n. When full, puts will complete, and be 
  buffered, but oldest elements in buffer will be dropped (not 
  transferred)."
  [n]
  [(if (= n 1) (BoxQueue. true false) (CircularObjectBuffer. (int n) false)) Channels$OverflowPolicy/DISPLACE])

(defn chan
  "Creates a channel with an optional buffer. If buf-or-n is a number, 
  will create and use a fixed buffer of that size."
  ([] (chan nil))
  ([buf-or-n] 
   (cond
     (nil? buf-or-n)    (TransferChannel.)
     (number? buf-or-n) (chan (buffer buf-or-n))
     :else              (QueueObjectChannel. (first buf-or-n) (second buf-or-n) false))))

(defn <!
  "takes a val from port. Must be called inside a (go ...) block. Will
  return nil if closed. Will park if nothing is available.
  
  Pulsar implementation: Identical to <!!. May be used outside go blocks as well."
  [port]
  (p/rcv port))

;; Unlike in core.async take! is a second-class citizen of this implementation. 
;; It gives no performance benefits over using go <!
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
  "puts a val into port. nil values are not allowed. Must be called
  inside a (go ...) block. Will park if no buffer space is available.
  
  Pulsar implementation: Identical to >!!. May be used outside go blocks as well. "
  [port val]
  (p/snd port val))

;; Unlike in core.async put! is a second-class citizen of this implementation. 
;; It gives no performance benefits over using go >!
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
  (TimeoutChannel/timeout msecs TimeUnit/MILLISECONDS))

(defn ^SelectAction do-alts
  [ports priority dflt]
  (let [^boolean priority (if priority true false)
        ^java.util.List ps (map (fn [port]
                                  (if (vector? port)
                                    (Selector/send ^SendPort (first port) (second port))
                                    (Selector/receive ^ReceivePort port)))
                                ports)
        ^SelectAction sa (if dflt
                           (Selector/trySelect priority ps)
                           (Selector/select    priority ps))]
    sa))

(defn alts!
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
(let [dflt (contains? opts :default)
      ^SelectAction sa (do-alts ports (:priority opts) dflt)]
  (if (and dflt (nil? sa))
    [(:default opts) :default]
    [(.message sa) (.port sa)])))


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
  (let [clauses (partition 2 clauses)
        opt? #(keyword? (first %)) 
        opts (filter opt? clauses)
        opts (zipmap (map first opts) (map second opts))
        clauses (remove opt? clauses)
        ports (mapcat #(let [x (first %)] (if (vector? x) x (list x))) clauses)
        exprs (mapcat #(let [x (first %) ; ports
                             e (second %)]; result-expr
                         (if (vector? x) (repeat (count x) e) (list e))) clauses)
        priority (:priority opts)
        dflt (contains? opts :default)
        sa (gensym "sa")]
    `(let [^co.paralleluniverse.strands.channels.SelectAction ~sa
           (do-alts (list ~@ports) ~priority ~dflt)]
       ~@(p/surround-with (when dflt
                   `(if (nil? ~sa) ~(:default opts)))
                 `(case (.index ~sa)
                    ~@(mapcat 
                        (fn [i e]
                          (let [b (if (and (list? e) (vector? (first e))) (first e) []) ; binding
                                a (if (and (list? e) (vector? (first e))) (rest e)  (list e))] ; action
                            `(~i (let ~(vec (concat (when-let [vr (first b)]  `(~vr (.message ~sa)))
                                                    (when-let [vr (second b)] `(~vr (.port ~sa)))))
                                   ~@a))))
                        (range) exprs))))))

(defn- f->chan
  [c f]
  (p/sfn []
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
  "takes a val from port. Will return nil if closed. Will block 
  if nothing is available."
  [port]
  (<! port))

(defn >!!
  "puts a val into port. nil values are not allowed. Will block if no 
  buffer space is available. Returns nil."
  [port val]
  (>! port val))

(defmacro alts!!
 "Like alts!, except takes will be made as if by <!!, and puts will
  be made as if by >!!, will block until completed, and not intended
  for use in (go ...) blocks.
 
  Pulsar implementation: identical to alt! and may be
  used in go blocks"
  [& args]
  `(alts! ~@args))

(defmacro alt!!
  "Like alt!, except as if by alts!!, will block until completed, and
  not intended for use in (go ...) blocks.
  
  Pulsar implementation: identical to alt! and may be
  used in go blocks"
  [& args]
  `(alt! ~@args))
