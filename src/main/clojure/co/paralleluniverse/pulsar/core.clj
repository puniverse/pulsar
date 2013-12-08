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

;;;
;;;
;;;
;;;

(ns co.paralleluniverse.pulsar.core
  "Pulsar is an implementation of lightweight threads (fibers),
  Go-like channles and Erlang-like actors for the JVM"
(:refer-clojure :exclude [promise await])
(:import [java.util.concurrent TimeUnit ExecutionException TimeoutException Future]
         [jsr166e ForkJoinPool ForkJoinTask]
         [co.paralleluniverse.strands Strand Stranded]
         [co.paralleluniverse.strands SuspendableCallable]
         [co.paralleluniverse.fibers DefaultFiberScheduler FiberScheduler Fiber Joinable FiberUtil]
         [co.paralleluniverse.fibers.instrument]
         [co.paralleluniverse.strands.channels Channel Channels Channels$OverflowPolicy ReceivePort SendPort 
          Selectable Selector SelectAction
          TickerChannelConsumer Topic ReceivePortGroup
          IntChannel LongChannel FloatChannel DoubleChannel
          IntSendPort LongSendPort FloatSendPort DoubleSendPort
          IntReceivePort LongReceivePort FloatReceivePort DoubleReceivePort
          DelayedVal]
         [co.paralleluniverse.pulsar ClojureHelper ChannelsHelper ClojureFiberAsync]
         ; for types:
         [clojure.lang Keyword Sequential IObj IFn IMeta IDeref ISeq IPersistentCollection IPersistentVector IPersistentMap])
(:require [co.paralleluniverse.pulsar.interop :refer :all]
          [clojure.core.typed :refer [ann def-alias Option AnyInteger]]))

;; ## clojure.core type annotations

(ann clojure.core/split-at (All [x] (Fn [Long (IPersistentCollection x) -> (IPersistentVector (IPersistentCollection x))])))
(ann clojure.core/coll? [Any -> Boolean :filters {:then (is (IPersistentCollection Any) 0) :else (! (IPersistentCollection Any) 0)}])
(ann clojure.core/partition-all (All [x] (Fn [Long (ISeq x) -> (ISeq (U (ISeq x) x))])))
(ann clojure.core/into (All [[xs :< (IPersistentCollection Any)]] (Fn [xs (IPersistentCollection Any) -> xs])))
(ann clojure.core/set-agent-send-executor! [java.util.concurrent.ExecutorService -> nil])
(ann clojure.core/set-agent-send-off-executor! [java.util.concurrent.ExecutorService -> nil])

;; ## Private util functions
;; These are internal functions aided to assist other functions in handling variadic arguments and the like.

(defmacro dbg [& body]
  {:no-doc true}
  `(let [x# ~@body
         y#    (if (seq? x#) (take 20 x#) x#)
         more# (if (seq? x#) (nthnext x# 20) false)]
     (println "dbg:" '~@body "=" y# (if more# "..." ""))
     x#))

;; from core.clj:
(defmacro ^{:private true} assert-args
  [& pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                 (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
     ~(let [more (nnext pairs)]
        (when more
          (list* `assert-args more)))))

(ann sequentialize (All [x y]
                        (Fn
                         [(Fn [x -> y]) ->
                          (Fn [x -> y]
                              [(ISeq x) -> (ISeq y)]
                              [x * -> (ISeq y)])])))
(defn- sequentialize
  "Takes a function of a single argument and returns a function that either takes any number of arguments or a
  a single sequence, and applies the original function to each argument or each element of the sequence"
  [f]
  (fn
    ([x] (if (seq? x) (map f x) (f x)))
    ([x & xs] (map f (cons x xs)))))

;;     (surround-with nil 4 5 6) -> (4 5 6)
;;     (surround-with '(1 2 3) 4 5 6) -> ((1 2 3 4 5 6))
;;     (surround-with '(1 (2)) '(3 4)) -> ((1 (2) (3 4)))
(ann surround-with [(ISeq Any) Any * -> (ISeq Any)])
(defn surround-with
  {:no-doc true}
  [expr & exprs]
  (if (nil? expr)
    exprs
    (list (concat expr exprs))))

;;     (deep-surround-with '(1 2 3) 4 5 6) -> (1 2 3 4 5 6)
;;     (deep-surround-with '(1 2 (3)) 4 5 6) -> (1 2 (3 4 5 6))
;;     (deep-surround-with '(1 2 (3 (4))) 5 6 7) -> (1 2 (3 (4 5 6 7)))
(ann ^:nocheck deep-surround-with [(ISeq Any) Any * -> (ISeq Any)])
(defn- deep-surround-with
  [expr & exprs]
  (if (not (coll? (last expr)))
    (concat expr exprs)
    (concat (butlast expr) (list (apply deep-surround-with (cons (last expr) exprs))))))

(ann ops-args [(ISeq (Vector* (Fn [Any -> Boolean]) Any)) (ISeq Any) -> (ISeq Any)])
(defn- ops-args
  "Used to simplify optional parameters in functions.
  Takes a sequence of [predicate? default] pairs, and a sequence of arguments. Tests the first predicate against
  the first argument. If the predicate succeeds, emits the argument's value; if not - the default, and tries the
  next pair with the argument. Any remaining arguments are copied to the output as-is."
  {:no-doc true}
  [pds xs]
  (if (seq pds)
    (let [[p? d] (first pds)
          x      (first xs)]
      (if (p? x)
        (cons x (ops-args (rest pds) (rest xs)))
        (cons d (ops-args (rest pds) xs))))
    (seq xs)))

(ann ^:nocheck kps-args [(ISeq Any) -> (Vector* (ISeq Any) (ISeq Any))])
(defn kps-args
  {:no-doc true}
  [args]
  (let [aps (partition-all 2 args)
        [opts-and-vals ps] (split-with #(keyword? (first %)) aps)
        options (into {} (map vec opts-and-vals))
        positionals (reduce into [] ps)]
    [options positionals]))

(ann merge-meta (All [[x :< clojure.lang.IObj] [y :< (IPersistentMap Keyword Any)]]
                     [x y -> (I x (IMeta y))]))
(defn merge-meta
  {:no-doc true}
  [s m]
  (with-meta s (merge-with #(%1) m (meta s))))

(defn apply-variadic
  "Calls a variadic function by applying a concat of all arguments with the last argument (which is supposedly a collection)"
  {:no-doc true}
  [f & args]
  (apply f (concat (butlast args) (last args))))

(ann keyword->timeunit [Keyword -> TimeUnit])
(defn- ^TimeUnit keyword->timeunit
  [x]
  (case x
    (:nanoseconds :nanos :ns)   TimeUnit/NANOSECONDS
    (:microseconds :us)         TimeUnit/MICROSECONDS
    (:milliseconds :millis :ms) TimeUnit/MILLISECONDS
    (:seconds :sec)             TimeUnit/SECONDS
    (:minutes :mins)            TimeUnit/MINUTES
    (:hours :hrs)               TimeUnit/HOURS
    :days                       TimeUnit/DAYS))

(ann ->timeunit [(U TimeUnit Keyword) -> TimeUnit])
(defn ^TimeUnit ->timeunit
  "Constructs an instance of `java.util.concurrent.TimeUnit`.
  If argument x is already an instance of `TimeUnit`, the function returns x.
  Otherwise, x *must* be a keyword, in which case the following conversion
  is performed:
  
  :nanoseconds | :nanos | :ns   -> TimeUnit/NANOSECONDS
  :microseconds | :us           -> TimeUnit/MICROSECONDS
  :milliseconds | :millis | :ms -> TimeUnit/MILLISECONDS
  :seconds | :sec               -> TimeUnit/SECONDS
  :minutes | :mins              -> TimeUnit/MINUTES
  :hours | :hrs                 -> TimeUnit/HOURS
  :days                         -> TimeUnit/DAYS
  "
  [x]
  (if (instance? TimeUnit x)
    x
    (keyword->timeunit x)))

(defn convert-duration
  "Converts a time duration from one time unit to another.
  x is the duration; `from-unit` and `to-unit` are the source
  and target units repsectively, given as either a j.u.c.TimeUnit instance
  or as a keyword, as specified by `->timeunit`."
  [x from-unit to-unit]
  (.convert (->timeunit to-unit) x (->timeunit from-unit)))

(ann unwrap-exception* [Throwable -> Throwable])
(defn unwrap-exception*
  {:no-doc true}
  [^Throwable e]
  (if
    (or (instance? ExecutionException e)
        (and (= (.getClass e) RuntimeException) (.getCause e)))
    (unwrap-exception* (.getCause e))
    e))

(defmacro unwrap-exception
  {:no-doc true}
  [& body]
  `(try
     ~@body
     (catch Exception e#
       (throw (unwrap-exception* e#)))))

;; ## Fork/Join Pool

(ann in-fj-pool? [-> Boolean])
(defn- in-fj-pool?
  "Returns true if we're running inside a fork/join pool; false otherwise."
  []
  (ForkJoinTask/inForkJoinPool))

(ann make-fj-pool [AnyInteger Boolean -> ForkJoinPool])
(defn ^ForkJoinPool make-fj-pool
  "Creates a new ForkJoinPool with the given parallelism and with the given async mode"
  [^Integer parallelism ^Boolean async]
  (ForkJoinPool. parallelism jsr166e.ForkJoinPool/defaultForkJoinWorkerThreadFactory nil async))

;; ## Suspendable functions
;; Only functions that have been especially instrumented can perform blocking actions
;; while running in a fiber.

(ann suspendable? [IFn -> Boolean])
(defn suspendable?
  "Returns true of a function has been instrumented as suspendable; false otherwise."
  [f]
  (or (instance? co.paralleluniverse.pulsar.IInstrumented f)
      (.isAnnotationPresent (.getClass ^Object f) co.paralleluniverse.fibers.Instrumented)))

(ann suspendable! (Fn [IFn -> IFn]
                      [IFn * -> (ISeq IFn)]
                      [(ISeq IFn) -> (ISeq IFn)]))
(defn suspendable!
  "Makes a function suspendable."
  ([f]
   (ClojureHelper/retransform f nil))
  ([x prot]
   (ClojureHelper/retransform x prot)))

(ann ->suspendable-callable [[Any -> Any] -> SuspendableCallable])
(defn ^SuspendableCallable ->suspendable-callable
  "wrap a clojure function as a SuspendableCallable"
  {:no-doc true}
  [f]
  (ClojureHelper/asSuspendableCallable f))

(defmacro sfn
  "Creates a suspendable function that can be used by a fiber or actor.
  Used exactly like `fn`"
  [& expr]
  `(suspendable! (fn ~@expr)))

(defmacro defsfn
  "Defines a suspendable function that can be used by a fiber or actor.
  Used exactly like `defn`"
  [& expr]
  `(do
     (defn ~@expr)
     (def ~(first expr) (suspendable! ~(first expr)))))

(defmacro letsfn
  "Defines a local suspendable function that can be used by a fiber or actor.
  Used exactly like `letfn`"
[fnspecs & body] 
`(let ~(vec (interleave (map first fnspecs) 
                        (map #(cons `sfn %) fnspecs)))
   ~@body))

(ann ^:nocheck strampoline (All [v1 v2 ...]
                                (Fn
                                 [(Fn [v1 v2 ... v2 -> Any]) v1 v2 ... v2 -> Any]
                                 [[-> Any] -> Any])))
(defsfn strampoline
  "A suspendable version of trampoline. Should be used to implement
  finite-state-machine actors.

  trampoline can be used to convert algorithms requiring mutual
  recursion without stack consumption. Calls f with supplied args, if
  any. If f returns a fn, calls that fn with no arguments, and
  continues to repeat, until the return value is not a fn, then
  returns that non-fn value. Note that if you want to return a fn as a
  final value, you must wrap it in some data structure and unpack it
  after trampoline returns."
  ([f]
     (let [ret (f)]
       (if (fn? ret)
         (recur ret)
         ret)))
  ([f & args]
     (strampoline #(apply f args))))

;; ## Fibers


(ann current-fiber [-> Fiber])
(defn current-fiber
  "Returns the currently running lightweight-thread or `nil` if none."
  []
  (Fiber/currentFiber))

(defn- current-scheduler []
  (when-let [^Fiber f (current-fiber)]
    (.getScheduler f)))

(ann default-fiber-scheduler FiberScheduler)
(def ^FiberScheduler default-fiber-scheduler
  "A global fiber scheduler. The scheduler uses all available processor cores."
  (DefaultFiberScheduler/getInstance))

(ann get-scheduler [-> FiberScheduler])
(defn ^FiberScheduler get-scheduler
  {:no-doc true}
  [^FiberScheduler scheduler]
  (or scheduler (current-scheduler) default-fiber-scheduler))

(ann fiber [String FiberScheduler AnyInteger [Any -> Any] -> Fiber])
(defn ^Fiber fiber
  "Creates, but does not start a new fiber (a lightweight thread) running in a fork/join pool.
  
  It is much preferable to use `spawn-fiber`."
  [& args]
  (let [[^String name ^FiberScheduler scheduler ^Integer stacksize f] (ops-args [[string? nil] [#(instance? FiberScheduler %) default-fiber-scheduler] [integer? -1]] args)]
    (Fiber. name (get-scheduler scheduler) (int stacksize) (->suspendable-callable f))))

(ann start [Fiber -> Fiber])
(defn start
  "Starts a fiber created with `fiber`."
  [^Fiber fiber]
  (.start fiber))

(defmacro spawn-fiber
  "Creates and starts a new fiber.
  
  f - the function to run in the fiber.
  args - (optional) arguments for the function
  
  Options:
  :name str     - the fiber's name
  :stack-size n - the fiber's initial stack size
  :scheduler    - the fiber schdeuler in which the fiber will run
  "
  {:arglists '([:name? :stack-size? :scheduler? f & args])}
  [& args]
  (let [[{:keys [^String name ^Integer stack-size ^FiberScheduler scheduler] :or {stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! ~(if (== (count body) 1) (first body) `(fn [] (apply ~@body))))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-scheduler ~scheduler) (int ~stack-size) (->suspendable-callable f#))]
       (.start fiber#))))

(ann current-fiber [-> Fiber])
(defn fiber->future
  "Takes a spawned fiber yields a future object that will
  invoke the function in another thread, and will cache the result and
  return it on all subsequent calls to deref/@. If the computation has
  not yet finished, calls to deref/@ will block, unless the variant
  of deref with timeout is used. See also - realized?."
  [f]
  (let [^Future fut (FiberUtil/toFuture f)]
    (reify
      clojure.lang.IDeref
      (deref [_] (.get fut))
      clojure.lang.IBlockingDeref
      (deref
       [_ timeout-ms timeout-val]
       (try (.get fut timeout-ms TimeUnit/MILLISECONDS)
          (catch TimeoutException e
            timeout-val)))
      clojure.lang.IPending
      (isRealized [_] (.isDone fut))
      Future
      (get [_] (.get fut))
      (get [_ timeout unit] (.get fut timeout unit))
      (isCancelled [_] (.isCancelled fut))
      (isDone [_] (.isDone fut))
      (cancel [_ interrupt?] (.cancel fut interrupt?)))))

(defmacro await
  "Calls f, which takes a callback of a single argument as its last parameter,
  with arguments args, and blocks the current fiber until the callback is called,
  then returns the value passed to the callback."
  [f & args]
  `(let [^co.paralleluniverse.pulsar.ClojureFiberAsync fa#
         (co.paralleluniverse.pulsar.ClojureFiberAsync.
           (fn [^co.paralleluniverse.pulsar.ClojureFiberAsync fa1#]
             (~f ~@args #(.complete fa1# %))))]
     (.run fa#)))


;; ## Strands
;; A strand is either a thread or a fiber.

(ann current-strand [-> Strand])
(defn ^Strand current-strand
  "Returns the currently running fiber (if running in fiber)
  or current thread (if not)."
  []
  (Strand/currentStrand))

(ann alive? [Strand -> Boolean])
(defn alive?
  "Tests whether or not a strand is alive. 
  A strand is alive if it has been started but has not yet died."
  [^Strand a]
  (.isAlive a))

(defsfn sleep
  "Suspends the current strand."
  ([^long ms]
   (Strand/sleep ms))
  ([^long timeout unit]
   (Strand/sleep (->timeunit unit))))

(defn spawn-thread
  "Creates and starts a new thread.
  
  f - the function to run in the thread.
  args - (optional) arguments to pass to the function
  
  Options:
  :name str     - the thread's name"
  {:arglists '([:name? f & args])}
  [& args]
  (let [[{:keys [^String name]} body] (kps-args args)]
    (let [f      (if (== (count body) 1) (first body) (fn [] (apply (first body) (rest body))))
          thread (if name (Thread. ^Runnable f name) (Thread. ^Runnable f))]
       (.start thread)
       thread)))

(ann join* [(U Joinable Thread) -> (Option Any)])
(defn- join*
  ([s]
   (if (instance? Joinable s)
     (unwrap-exception
      (.get ^Joinable s))
     (Strand/join s)))
  ([timeout unit s]
   (if (instance? Joinable s)
     (unwrap-exception
      (.get ^Joinable s timeout (->timeunit unit)))
     (Strand/join s timeout (->timeunit unit)))))

(ann join (Fn [(U Joinable Thread) -> (Option Any)]
              [(Sequential (U Joinable Thread)) -> (ISeq Any)]))
(defn join
  "Awaits the termination of the given strand or strands, and returns
  their result, if applicable.
  
  If a single strand is given, its result is returned;
  if a collection - then a collection of the repsective results.
  
  Note that for threads, the result is always `nil`, as threads don't return a value.
  
  If a timeout is supplied and it elapses before the strand has terminated,
  a j.u.c.TimeoutException is thrown.
  
  s       - either a strand or a collection of strands.
  timeout - how long to wait for the strands termination
  unit    - the unit of the timeout duration. TimeUnit or keyword as in `->timeunit`"
  ([s]
   (if (coll? s)
     (doall (map join* s))
     (join* s)))
  ([timeout unit s]
   (if (coll? s)
     (loop [nanos (long (convert-duration timeout unit :nanos))
            res []
            ss s]
       (when (not (pos? nanos))
         (throw (TimeoutException.)))
       (if (seq? ss)
         (let [start (long (System/nanoTime))
               r (join* (first ss) nanos TimeUnit/NANOSECONDS)]
           (recur (- nanos (- (System/nanoTime) start))
                  (conj res r)
                  (rest ss)))
         (seq res)))
     (join* timeout unit s))))

;; ## Promises

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

;; ## Channels

(ann channel (Fn [AnyInteger -> Channel]
                 [-> Channel]))
(defn ^Channel channel
  "Creates a new channel.
  
  Optional arguments:
  capacity        - specifies how many messages the channel can contain (until they are consumed)
                    * A value of `0` designates a *transfer channel*, that blocks both `snd` and `rcv` 
                      until a corresponding operation (`rcv` or `snd` respectively) is called.
                    * A value of `-1` creates an unbounded channel.
  
                    default: 0
  
  overflow-policy - specifies what `snd` does when the channel's capacity is exhausted.
                    May be one of:
                    * :throw    - throws an exception.
                    * :block    - blocks until a message is consumed and room is available
                    * :drop     - the message is silently dropped
                    * :displace - the old message waiting in the queue is discarded to make room for the new message.
                    
                    default: :block
  
  The default channel capacity is 0 and the default policy is :block"
  ([capacity overflow-policy] (Channels/newChannel (int capacity) (keyword->enum Channels$OverflowPolicy overflow-policy)))
  ([capacity]                 (Channels/newChannel (int capacity)))
  ([]                         (Channels/newChannel 0)))

(defn ^TickerChannelConsumer ticker-consumer
  "Creates a rcv-port (read-only channel) that returns messages from a *ticker channel*.
  A ticker channel is a bounded channel with an overflow policy of :displace.
  
  Different ticker consumers are independent (a message received from one is not removed from others),
  and guarantee monotonicty (messages are received in order), but if messages are sent to the
  ticker channel faster than they are consumed then messages can be lost."
  [^Channel ticker]
  (cond 
    (instance? IntChannel ticker)    (Channels/newTickerConsumerFor ^IntChannel ticker)
    (instance? LongChannel ticker)   (Channels/newTickerConsumerFor ^LongChannel ticker)
    (instance? FloatChannel ticker)  (Channels/newTickerConsumerFor ^FloatChannel ticker)
    (instance? DoubleChannel ticker) (Channels/newTickerConsumerFor ^DoubleChannel ticker)
    :else                            (Channels/newTickerConsumerFor ticker)))

(ann snd (All [x] [Channel x -> x]))
(defsfn snd
  "Sends a message to a channel.
  If the channel's overflow policy is `:block` than this function will block
  if the channels' capacity is exceeded."
  [^SendPort channel message]
  (.send channel message))

(ann snd (All [x] [Channel x -> x]))
(defn try-snd
  "Tries to immediately send a message to a channel.
  If the channel's capacity is exceeded, this function fails and returns `false`.
  Returns `true` if the operation succeeded; `false` otherwise.
  This function never blocks."
  [^SendPort channel message]
  (.trySend channel message))

(ann rcv (Fn [Channel -> Any]
             [Channel Long (U TimeUnit Keyword) -> (Option Any)]))
(defsfn rcv
  "Receives a message from a channel or a channel group.
  This function will block until a message is available or until the timeout,
  if specified, expires.
  If a timeout is given, and it expires, rcv returns nil.
  Otherwise, the message is returned."
  ([^ReceivePort channel]
   (.receive channel))
  ([^ReceivePort channel timeout unit]
   (.receive channel (long timeout) (->timeunit unit))))

(defn try-rcv
  "Attempts to immediately (without blocking) receive a message from a channel.
  Returns the message if one is immediately available; `nil` otherwise.
  This function never blocks."
  [^ReceivePort channel]
  (.tryReceive channel))

(defn close!
  "Closes a channel.
  Messages already in the channel will be received, but all future attempts at `snd`
  will silently discard the message."
  [channel]
  (cond
    (instance? SendPort channel)    (.close ^SendPort channel)
    (instance? ReceivePort channel) (.close ^ReceivePort channel)
    :else (throw (IllegalArgumentException. (str (.toString ^Object channel) " is not a channel")))))

(defn closed?
  "Tests whether a channel has been closed and contains no more messages that 
  can be received."
[^ReceivePort channel]
(.isClosed channel))

(defn ^ReceivePort rcv-group
  "Creates a receive-port (a read-only channel) from a group of channels.
  Receiving a message from the group will return the next message available from
  any of the channels in the group.
  A message received from the group is consumed (removed) from the original member
  channel to which it has been sent."
  ([ports]
   (ReceivePortGroup. ^java.util.Collection ports))
  ([port & ports]
   (ReceivePortGroup. ^java.util.Collection (cons port ports))))

(defn topic
  "Creates a new topic.
  A topic is a send-port (a write-only channel) that forwards every message sent to it
  to a group of subscribed channels.
  Use `subscribe!` and `unsubscribe!` to subscribe and unsubscribe a channel to or from
  the topic."
  []
  (Topic.))

(defn subscribe!
  "Subscribes a channel to a topic.
  The subscribed channel will receive all messages sent to the topic."
  [^Topic topic ^SendPort channel]
  (.subscribe topic channel))

(defn unsubscribe!
  "Unsubscribes a channel from a topic.
  The channel will stop receiving messages sent to the topic."
  [^Topic topic ^SendPort channel]
  (.unsubscribe topic channel))

(defsfn ^SelectAction do-sel
  {:no-doc true}
  [ports priority millis]
  (let [^TimeUnit unit (when millis TimeUnit/MILLISECONDS)
        millis (long (or millis 0))]
    (Selector/select 
      ^boolean (if priority true false)
      millis unit
      ^java.util.List (doall (map #(if (vector? %)
                                     (Selector/send ^SendPort (first %) (second %))
                                     (Selector/receive ^ReceivePort %))
                                  ports)))))

(defsfn sel
  "Performs up to one of several given channel operations.
  sel takes a collection containing *channel operation descriptors*. A descriptor is 
  either a channel or a pair (vector) of a channel and a message. 
  Each channel in the sequence represents a `rcv` attempt, and each channel-message pair 
  represents a `snd` attempt. 
  The `sel` function performs at most one operation on the sequence, a `rcv` or a `snd`, 
  which is determined by the first operation that can succeed. If no operation can be 
  carried out immediately, `sel` will block until an operation can be performed, or the
  optionally specified timeout expires.
  If two or more operations are available at the same time, one of them will be chosen
  at random, unless the `:priority` option is set to `true`.
  
  Options:
  :priority bool -  If set to `true`, then whenever two or more operations are available
                    the first among them, in the order they are listed in the `ports` collection,
                    will be the one executed.
  :timeout millis - If timeout is set and expires before any of the operations are available,
                    the function will return `nil`
  
  Returns:
  If an operation succeeds, returns a vector `[m ch]` with `m` being the message received if the
  operation is a `rcv`, or `nil` if it's a `snd`, and `ch` is the channel on which the succesful
  opration was performed.
  If a timeout is set and expires before any of the operations are available, returns `nil`."
  [ports & {:as opts}]
  (let [^SelectAction sa (do-sel ports (:priority opts) (:timeout opts))]
    (when sa
      [(.message sa) (.port sa)])))

(defmacro select
  "Performs a very similar operation to `sel`, but allows you to specify an action to perform depending 
  on which operation has succeeded.
  Takes an even number of expressions, ordered as (ops1, action1, ops2, action2 ...) with the ops being 
  a channel operation descriptior (remember: a descriptor is either a channel for an `rcv` operation, 
  or a vector of a channel and a message specifying a `snd` operation) or a collection of descriptors, 
  and the actions are Clojure expressions. 
  Like `sel`, `select` performs at most one operation, in which case it will run the operation's 
  respective action and return its result.

  An action expression can bind values to the operations results. 
  The action expression may begin with a vector of one or two symbols. In that case, the first symbol 
  will be bound to the message returned from the successful receive in the respective ops clause 
  (or `nil` if the successful operation is a `snd`), and the second symbol, if present, will be bound 
  to the successful operation's channel.

  Like `sel`, `select` blocks until an operation succeeds, or, if a `:timeout` option is specified, 
  until the timeout (in milliseconds) elapses. If a timeout is specfied and elapses, `select` will run 
  the action in an optional `:else` clause and return its result, or, if an `:else` clause is not present, 
  `select` will return `nil`.

  Example:

  (select :timeout 100 
         c1 ([v] (println \"received\" v))
         [[c2 m2] [c3 m3]] ([v c] (println \"sent to\" c))
         :else \"timeout!\")

  In the example, if a message is received from channel `c1`, then it will be printed. 
  If a message is sent to either `c2` or `c3`, then the identity of the channel will be printed, 
  and if the 100 ms timeout elapses then \"timeout!\" will be printed."
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
        timeout (:timeout opts)
        dflt (contains? opts :else)
        sa (gensym "sa")]
    `(let [^co.paralleluniverse.strands.channels.SelectAction ~sa
           (do-sel (list ~@ports) ~priority ~timeout)]
       ~@(surround-with 
           (when dflt
             `(if (nil? ~sa) ~(:else opts)))
           `(case (.index ~sa)
              ~@(mapcat 
                  (fn [i e]
                    (let [b (if (and (list? e) (vector? (first e))) (first e) []) ; binding
                          a (if (and (list? e) (vector? (first e))) (rest e)  (list e))] ; action
                      `(~i (let ~(vec (concat (when-let [vr (first b)]  `(~vr (.message ~sa)))
                                              (when-let [vr (second b)] `(~vr (.port ~sa)))))
                             ~@a))))
                  (range) exprs))))))

;; ### Primitive channels

(ann int-channel (Fn [AnyInteger -> IntChannel]
                     [-> IntChannel]))
(defn ^IntChannel int-channel
  "Creates an int channel"
  ([size overflow-policy] (Channels/newIntChannel (int size) (keyword->enum Channels$OverflowPolicy overflow-policy)))
  ([size]                 (Channels/newIntChannel (int size)))
  ([]                     (Channels/newIntChannel -1)))

(defmacro snd-int
  "Sends an int value to an int-channel.
  
  See: `snd`"
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendInt ^co.paralleluniverse.strands.channels.IntSendPort ~channel (int ~message)))

(defmacro try-snd-int
  "Tries to immediately send an int value to an int-channel.
  Returns `true` if successful, `false` otherwise.
  
  See: `try-snd`"
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/trySendInt ^co.paralleluniverse.strands.channels.IntSendPort ~channel (int ~message)))

(defmacro rcv-int
  "Receives an int value from an int-channel.
  
  See: `rcv`"
  ([channel]
   `(int (.receiveInt ^co.paralleluniverse.strands.channels.IntReceivePort ~channel)))
  ([channel timeout unit]
   `(int (.receiveInt ^co.paralleluniverse.strands.channels.IntReceivePort ~channel (long ~timeout) (->timeunit ~unit)))))

(ann long-channel (Fn [AnyInteger -> LongChannel]
                      [-> LongChannel]))
(defn ^LongChannel long-channel
  "Creates a long channel"
  ([size overflow-policy] (Channels/newLongChannel (int size) (keyword->enum Channels$OverflowPolicy overflow-policy)))
  ([size]                 (Channels/newLongChannel (int size)))
  ([]                     (Channels/newLongChannel -1)))

(defmacro snd-long
  "Sends a long value to a long-channel.  
  
  See: `snd`"
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendLong ^co.paralleluniverse.strands.channels.LongSendPort ~channel (long ~message)))

(defmacro try-snd-long
  "Tries to immediately send a long value to a long-channel.
  Returns `true` if successful, `false` otherwise.  
  
  See: `try-snd`"
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/trySendLong ^co.paralleluniverse.strands.channels.LongSendPort ~channel (long ~message)))

(defmacro rcv-long
  "Receives a long value from a long-channel.
  
  See: `rcv`"
  ([channel]
   `(long (.receiveLong ^co.paralleluniverse.strands.channels.LongReceivePort ~channel)))
  ([channel timeout unit]
   `(long (.receiveLong ^co.paralleluniverse.strands.channels.LongReceivePort ~channel (long ~timeout) (->timeunit ~unit)))))

(ann float-channel (Fn [AnyInteger -> FloatChannel]
                       [-> FloatChannel]))
(defn ^FloatChannel float-channel
  "Creates a float channel"
  ([size overflow-policy] (Channels/newFloatChannel (int size) (keyword->enum Channels$OverflowPolicy overflow-policy)))
  ([size]                 (Channels/newFloatChannel (int size)))
  ([]                     (Channels/newFloatChannel -1)))

(defmacro snd-float
  "Sends a float value to a float-channel.  
  
  See: `snd`"
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendFloat ^co.paralleluniverse.strands.channels.FloatSendPort ~channel (float ~message)))

(defmacro try-snd-float
  "Tries to immediately send a float value to a float-channel.
  Returns `true` if successful, `false` otherwise.  
  
  See: `try-snd`"
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/trySendFloat ^co.paralleluniverse.strands.channels.FloatSendPort ~channel (float ~message)))

(defmacro rcv-float
  "Receives a float value from a float-channel.
  
  See: `rcv`"
  ([channel]
   `(float (.receiveFloat ^co.paralleluniverse.strands.channels.FloatReceivePort ~channel)))
  ([channel timeout unit]
   `(float (.receiveFloat ^co.paralleluniverse.strands.channels.FloatReceivePort ~channel (long ~timeout) (->timeunit ~unit)))))

(ann double-channel (Fn [AnyInteger -> DoubleChannel]
                        [-> DoubleChannel]))
(defn ^DoubleChannel double-channel
  "Creates a double channel"
  ([size overflow-policy] (Channels/newDoubleChannel (int size) (keyword->enum Channels$OverflowPolicy overflow-policy)))
  ([size]                 (Channels/newDoubleChannel (int size)))
  ([]                     (Channels/newDoubleChannel -1)))

(defmacro snd-double
  "Sends a double value to a double-channel.  
  
  See: `snd`"
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendDouble ^co.paralleluniverse.strands.channels.DoubleSendPort ~channel (double ~message)))

(defmacro try-snd-double
  "Tries to immediately send a double value to a double-channel.
  Returns `true` if successful, `false` otherwise.  
  
  See: `try-snd`"
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/trySendDouble ^co.paralleluniverse.strands.channels.DoubleSendPort ~channel (double ~message)))

(defmacro rcv-double
  "Receives a double value from a double-channel.
  
  See: `rcv`"
  ([channel]
   `(double (.receiveDouble ^co.paralleluniverse.strands.channels.DoubleReceivePort ~channel)))
  ([channel timeout unit]
   `(double (.receiveDouble ^co.paralleluniverse.strands.channels.DoubleReceivePort ~channel (long ~timeout) (->timeunit ~unit)))))


