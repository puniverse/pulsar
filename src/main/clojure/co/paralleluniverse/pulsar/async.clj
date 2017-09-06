; Pulsar: lightweight threads and Erlang-like actors for Clojure.
; Copyright (C) 2013-2016, Parallel Universe Software Co. All rights reserved.
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
; Docstrings and "OPS" functions are derived from core.async (https://github.com/clojure/core.async).
; Copyright (C) 2013 Rich Hickey and contributors.
; Distributed under the Eclipse Public License, the same as Clojure.
;
(ns co.paralleluniverse.pulsar.async
  "Fiber-based implementation of [org.clojure/core.async \"0.1.346.0-17112a-alpha\"]"
  (:refer-clojure :exclude [reduce into merge map take partition partition-by last])
  (:require
    [co.paralleluniverse.pulsar.core :as p :refer [defsfn sfn]])
  (:import
    [co.paralleluniverse.strands.channels Channel QueueObjectChannel TransferChannel TimeoutChannel Channels$OverflowPolicy SendPort ReceivePort Selector SelectAction Channels ReceivePortGroup Mix$SoloEffect Mix$State Mix$Mode]
    [co.paralleluniverse.strands.queues ArrayQueue BoxQueue CircularObjectBuffer]
    [java.util Collection]
    [java.util.concurrent TimeUnit Executors Executor]
    [com.google.common.util.concurrent ThreadFactoryBuilder]
    (java.util List)
    (co.paralleluniverse.strands Strand SuspendableAction1 SuspendableAction2 SuspendableCallable)
    (co.paralleluniverse.pulsar.async DelegatingChannel CoreAsyncSendPort IdentityPipeline PredicateSplitSendPort ParallelTopic PubSplitSendPort)
    (co.paralleluniverse.common.util Function2 Pair)
    (com.google.common.base Predicate Function)
    (co.paralleluniverse.strands.channels.transfer Pipeline)))

(alias 'core 'clojure.core)

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

(defn unblocking-buffer?
  "Returns true if a channel created with buffer will never block. That is to say,
   puts into this buffer will never cause the buffer to be full. "
  [[_ policy]]
  (and (not= policy Channels$OverflowPolicy/BLOCK) (not= policy Channels$OverflowPolicy/BACKOFF)))

(defsfn ^:private ex-handler [ex]
  (-> (Thread/currentThread)
      .getUncaughtExceptionHandler
      (.uncaughtException (Thread/currentThread) ex))
  nil)

(defn rx-chan [chan xform exh]
  "Returns a new transforming channel based on the one passed as a first argument. The given transducer will
  be applied to the add (send) function."
  (let [add-reducer-builder
          (fn [snd-op]
            (sfn
              ([x] x)
              ([x v] (snd-op v) x)))
        handle-builder
          (fn [snd-op]
            (sfn [x exh t]
              (let [else ((or (p/suspendable! exh) ex-handler) t)]
                (if (nil? else)
                  x
                  ((add-reducer-builder snd-op) x else)))))
        xf-add-reducer-builder
          (fn [snd-op]
            (let [add-reducer (add-reducer-builder snd-op)
                  handle (handle-builder snd-op)
                  add! (if xform (p/suspendable! (xform add-reducer)) add-reducer)]
              (sfn
                ([x]
                  (try
                    (add! x)
                    (catch Throwable t
                      (handle x exh t))))
                ([x v]
                  (try
                    (add! x v)
                    (catch Throwable t
                      (handle x exh t)))))))
        px
          (CoreAsyncSendPort.
            chan
            (p/sreify SuspendableAction1
              (call [_ v] ((xf-add-reducer-builder (sfn [v] (.send ^Channel chan v))) chan v))))]
     (cond
       (and (nil? xform) (nil? exh))
         chan
       :else
         (DelegatingChannel. px chan chan))))

(defn chan
  "Creates a channel with an optional buffer, an optional transducer
   (like (map f), (filter p) etc or a composition thereof), and an
   optional exception-handler. If buf-or-n is a number, will create
   and use a fixed buffer of that size. If a transducer is supplied a
   buffer must be specified. ex-handler must be a fn of one argument -
   if an exception occurs during transformation it will be called with
   the Throwable as an argument, and any non-nil return value will be
   placed in the channel."
  ([] (chan nil))
  ([buf-or-n] (chan buf-or-n nil))
  ([buf-or-n xform] (chan buf-or-n xform nil))
  ([buf-or-n xform ex-handler]
   (cond
     (number? buf-or-n) (chan (buffer buf-or-n) xform ex-handler)
     (nil? buf-or-n)    (do (when xform (assert buf-or-n "buffer must be supplied when transducer is")) (rx-chan (TransferChannel.) nil nil))
     :else              (let [buf (first buf-or-n) policy (second buf-or-n)] (rx-chan (QueueObjectChannel. buf policy false false) xform ex-handler)))))

(defsfn <!
  "Takes a val from port. Must be called inside a (go ...) block. Will
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

(defsfn >!
  "Puts a val into port. nil values are not allowed. Must be called
   inside a (go ...) block. Will park if no buffer space is available.
   Returns true unless port is already closed.

   Pulsar implementation: Identical to >!!. May be used outside go blocks as well."
  [port val]
  (if (not (p/closed? port))
    (do (p/snd port val) true)
    false))

;; Unlike in core.async put! is a second-class citizen of this implementation.
;; It gives no performance benefits over using go >!
(defn put!
  "Asynchronously puts a val into port, calling fn1 (if supplied) when
   complete, passing false iff port is already closed. nil values are
   not allowed. If on-caller? (default true) is true, and the put is
   immediately accepted, will call fn1 on calling thread.  Returns
   true unless port is already closed."
  ([port val] (put! port val nil))
  ([port val fn1] (put! port val fn1 true))
  ([port val fn1 on-caller?]
    (if (not (p/closed? port))
      (if-let [res (and on-caller? (p/try-snd port val))]
        (when fn1 (fn1 res) true)
        (p/spawn-fiber #(let [res (p/snd port val)]
                           (when fn1 (fn1 res)))))
      (do
        (when fn1 (fn1 false))
        false))))

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

(defsfn ^:private do-alts-internal
  "Returns a SelectAction given a set of selection operations and an options map"
  [ports opts]
  (let [^boolean priority (if (:priority opts) true false)
        ^List ps (core/map (fn [port]
                                  (if (vector? port)
                                    (Selector/send ^SendPort (first port) (second port))
                                    (Selector/receive ^ReceivePort port)))
                                ports)
        ^SelectAction sa (if (:default opts)
                           (Selector/trySelect priority ps)
                           (Selector/select    priority ps))]
    sa))

(defsfn do-alts
  "Returns derefable [val port] if immediate, nil if enqueued"
  [fret ports opts]
  (let [^SelectAction sa (do-alts-internal ports opts)
        v [(.message sa) (.port sa)]]
    (if (and (contains? opts :default) (nil? sa))
      [(:default opts) :default]
      (if fret (fret v) v))))

(defsfn alts!
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
   depended upon for side effects.

   Pulsar implementation: Identical to alts!!. May be used outside go blocks as well."
  [ports & {:as opts}]
  (do-alts identity ports opts))

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
   characteristics are those of alts!.

   Pulsar implementation: Identical to alt!!. May be used outside go blocks as well."
  [& clauses]
  (let [clauses (core/partition 2 clauses)
        opt? #(keyword? (first %))
        opts (filter opt? clauses)
        opts (zipmap (core/map first opts) (core/map second opts))
        clauses (remove opt? clauses)
        ports (mapcat #(let [x (first %)] (if (vector? x) x (list x))) clauses)
        exprs (mapcat #(let [x (first %) ; ports
                             e (second %)]; result-expr
                         (if (vector? x) (repeat (count x) e) (list e))) clauses)
        priority (:priority opts)
        dflt (contains? opts :default)
        sa (gensym "sa")]
    `(let [^SelectAction ~sa
           (do-alts-internal (list ~@ports) ~opts)]
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

(defrecord ^:private FToChanExc [^Throwable exc])
(alter-meta! #'->FToChanExc assoc :private true)
(alter-meta! #'map->FToChanExc assoc :private true)

(defn- f-to-chan
  [c f]
  (p/sfn []
    (let [ret (try (f)
                (catch Throwable t
                  (->FToChanExc t)))]
      (when-not (or (nil? ret) (instance? FToChanExc ret))
        (>! c ret))
      (close! c)
      (when (instance? FToChanExc ret)
        (throw (:exc ret))))))

(defonce ^:private ^Executor thread-macro-executor
  (Executors/newCachedThreadPool (-> (ThreadFactoryBuilder.) (.setNameFormat "async-thread-%d") (.setDaemon true) (.build))))

(defn thread-call
  "Executes f in another thread, returning immediately to the calling
   thread. Returns a channel which will receive the result of calling
   f when completed."
  [f]
  (let [c (chan 1)]
    (let [binds (clojure.lang.Var/getThreadBindingFrame)]
      (.execute thread-macro-executor
                (fn []
                  (clojure.lang.Var/resetThreadBindingFrame binds)
                  ((f-to-chan c f)))))
    c))

(defmacro thread
  "Executes the body in another thread, returning immediately to the
   calling thread. Returns a channel which will receive the result of
   the body when completed."
  [& body]
  `(thread-call (fn [] ~@body)))

;; This function is not part of core.async. It is provided here for symmetry with thread-call
(defn fiber-call
  "Executes f in another fiber, returning immediately to the calling
   strand. Returns a channel which will receive the result of calling
   f when completed."
  [f]
  (let [c (chan 1)]
    (p/spawn-fiber (f-to-chan c (p/suspendable! f)))
    c))

;; This function is not part of core.async. It is provided here for symmetry with thread
(defmacro fiber
  "Executes the body in another fiber, returning immediately to the
   calling strand. Returns a channel which will receive the result of
   the body when completed."
  [& body]
  `(fiber-call (fn [] ~@body)))

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

(def <!!
  "Takes a val from port. Will return nil if closed. Will block
   if nothing is available.

   Pulsar implementation: Identical to <!. May be used outside go blocks as well."
  <!)

(def >!!
  "Puts a val into port. nil values are not allowed. Will block if no
   buffer space is available. Returns true unless port is already closed.

   Pulsar implementation: Identical to >!. May be used outside go blocks as well."
  >!)

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

;; OPS

(defmacro go-loop
  "Like (go (loop ...))"
  [bindings & body]
  `(go (loop ~bindings ~@body)))

(defn- bounded-count
  "Returns the smaller of n or the count of coll, without examining
   more than n items if coll is not counted"
  [n coll]
  (if (counted? coll)
    (min n (count coll))
    (loop [i 0 s (seq coll)]
      (if (and s (< i n))
        (recur (inc i) (next s))
        i))))

(defsfn onto-chan
  "Puts the contents of coll into the supplied channel.

   By default the channel will be closed after the items are copied,
   but can be determined by the close? parameter.

   Returns a channel which will close after the items are copied."
  ([ch coll] (onto-chan ch coll true))
  ([ch coll close?]
    (go-loop [vs (seq coll)]
             (if (and vs (>! ch (first vs)))
               (recur (next vs))
               (when close?
                 (close! ch))))))

(defsfn to-chan
  "Creates and returns a channel which contains the contents of coll,
   closing when exhausted."
  [coll]
  (let [ch (chan (bounded-count 100 coll))]
    (onto-chan ch coll)
    ch))

(defsfn ^:private last
  [ch]
  (loop [ret nil]
    (let [v (<! ch)]
      (if (nil? v)
        ret
        (recur v)))))

(defsfn reduce
  "f should be a function of 2 arguments. Returns a channel containing
   the single result of applying f to init and the first item from the
   channel, then applying f to that result and the 2nd item, etc. If
   the channel closes without yielding items, returns init and f is not
   called. ch must close before reduce produces a result."
  [f init ch]
  (fiber
    (last
      (DelegatingChannel.
        ch
        (Channels/reduce
          ch
          (reify
            Function2
              (apply [_ accum v] (f accum v)))
          init)
        ch))))

(defsfn pipe
  "Takes elements from the from channel and supplies them to the to
   channel. By default, the to channel will be closed when the from
   channel closes, but can be determined by the close? parameter. Will
   stop consuming the from channel if the to channel closes."
  ([from to] (pipe from to true))
  ([from to close?]
    (p/spawn-fiber #(.run (IdentityPipeline. from to 0 close?)))))

(defsfn split
  "Takes a predicate and a source channel and returns a vector of two
   channels, the first of which will contain the values for which the
   predicate returned true, the second those for which it returned
   false.

   The out channels will be unbuffered by default, or two buf-or-ns can
   be supplied. The channels will close after the source channel has
   closed."
  ([p ch] (split p ch nil nil))
  ([p ch t-buf-or-n f-buf-or-n]
    (let [tc (chan t-buf-or-n)
          fc (chan f-buf-or-n)]
      (pipe ch (PredicateSplitSendPort.
                 (reify Predicate
                   (apply [_ v] (p v)))
                 tc fc))
      [tc fc])))

(defsfn take
  "Returns a channel that will return, at most, n items from ch. After n items
   have been returned, or ch has been closed, the return channel will close.

   The output channel is unbuffered by default, unless buf-or-n is given."
  ([n ch]
    (take n ch nil))
  ([n ch buf-or-n]
    (let [out (chan buf-or-n)]
      (pipe (Channels/take ch n) out)
      out)))

(defsfn merge
  "Takes a collection of source channels and returns a channel which
   contains all values taken from them. The returned channel will be
   unbuffered by default, or a buf-or-n can be supplied. The channel
   will close after all the source channels have closed."
  ([chs] (merge chs nil))
  ([chs buf-or-n]
    (let [out (chan buf-or-n)]
      (pipe (Channels/group ^Collection chs) out)
      out)))

(defprotocol Mix
  (admix* [m ch])
  (unmix* [m ch])
  (unmix-all* [m])
  (toggle* [m state-map])
  (solo-mode* [m mode]))

(defprotocol Mux
  (muxch* [_]))

(defsfn mix
   "Creates and returns a mix of one or more input channels which will
    be put on the supplied out channel. Input sources can be added to
    the mix with 'admix', and removed with 'unmix'. A mix supports
    soloing, muting and pausing multiple inputs atomically using
    'toggle', and can solo using either muting or pausing as determined
    by 'solo-mode'.

    Each channel can have zero or more boolean modes set via 'toggle':

    :solo - when true, only this (ond other soloed) channel(s) will appear
            in the mix output channel. :mute and :pause states of soloed
            channels are ignored. If solo-mode is :mute, non-soloed
            channels are muted, if :pause, non-soloed channels are
            paused.

    :mute - muted channels will have their contents consumed but not included in the mix
    :pause - paused channels will not have their contents consumed (and thus also not included in the mix)"
   [out]
   (let [rp-array #(let [a (make-array ReceivePort 1)] (aset ^"[Lco.paralleluniverse.strands.channels.ReceivePort;" a 0 ^ReceivePort %) a)
         empty-rp-array (make-array ReceivePort 0)
         flatten-coll-map (fn [coll-map f-key f-val]
                            (reduce-kv
                              (fn [res coll val]
                                (core/merge
                                  res
                                  (core/map
                                    (fn [a] {(f-key a) (f-val val)})
                                    coll)))
                              {} coll-map))
         to-flattened-c-state (fn [c-state-map] (flatten-coll-map c-state-map identity identity))
         to-mix-state (fn [flattened-c-state]
                        (Mix$State.
                          (cond
                            (:mute flattened-c-state) Mix$Mode/MUTE
                            (:pause flattened-c-state) Mix$Mode/PAUSE
                            :else Mix$Mode/NORMAL)
                          (:solo flattened-c-state)))
         to-mix-state-map (fn [state-map]
                            (flatten-coll-map state-map identity #(to-mix-state (to-flattened-c-state %))))
         solo-modes #{:mute :pause}
         g (ReceivePortGroup. true)
         m (p/sreify
             Mux
             (muxch* [_] out)
             Mix
             (admix* [_ ch] (.add g (rp-array ch)))
             (unmix* [_ ch] (.remove g (rp-array ch)))
             (unmix-all* [_] (.remove g empty-rp-array))
             (toggle* [_ state-map] (.setState g (to-mix-state-map state-map)))
             (solo-mode* [this mode]
               (assert (solo-modes mode) (str "mode must be one of: " solo-modes))
               (.setSoloEffect ^co.paralleluniverse.strands.channels.Mix this (condp mode = :mute Mix$SoloEffect/MUTE_OTHERS :pause Mix$SoloEffect/PAUSE_OTHERS))))]
     (pipe g out)
     m))

(defn admix
  "Adds ch as an input to the mix"
  [mix ch]
  (admix* mix ch))

(defn unmix
  "Removes ch as an input to the mix"
  [mix ch]
  (unmix* mix ch))

(defn unmix-all
  "removes all inputs from the mix"
  [mix]
  (unmix-all* mix))

(defn toggle
  "Atomically sets the state(s) of one or more channels in a mix. The
   state map is a map of channels -> channel-state-map. A
   channel-state-map is a map of attrs -> boolean, where attr is one or
   more of :mute, :pause or :solo. Any states supplied are merged with
   the current state.

   Note that channels can be added to a mix via toggle, which can be
   used to add channels in a particular (e.g. paused) state."
  [mix state-map]
  (toggle* mix state-map))

(defn solo-mode
  "Sets the solo mode of the mix. mode must be one of :mute or :pause"
  [mix mode]
  (solo-mode* mix mode))

(defprotocol Mult
  (tap* [m ch close?])
  (untap* [m ch])
  (untap-all* [m]))

(defsfn mult
  "Creates and returns a mult(iple) of the supplied channel. Channels
   containing copies of the channel can be created with 'tap', and
   detached with 'untap'.

   Each item is distributed to all taps in parallel and synchronously,
   i.e. each tap must accept before the next item is distributed. Use
   buffering/windowing to prevent slow taps from holding up the mult.

   Items received when there are no taps get dropped.

   If a tap puts to a closed channel, it will be removed from the mult."
  [ch]
  (let [t (ParallelTopic. 0)
        m (p/sreify
            Mux
            (muxch* [_] ch)

            Mult
            (tap* [_ ch close?] (.subscribe t ch close?))
            (untap* [_ ch] (.unsubscribe t ch) nil)
            (untap-all* [_] (.unsubscribeAll t) nil))]
    (pipe ch t)
    m))

(defn tap
  "Copies the mult source onto the supplied channel.

   By default the channel will be closed when the source closes,
   but can be determined by the close? parameter."
  ([mult ch] (tap mult ch true))
  ([mult ch close?] (tap* mult ch close?) ch))

(defn untap
  "Disconnects a target channel from a mult"
  [mult ch]
  (untap* mult ch))

(defn untap-all
  "Disconnects all target channels from a mult"
  [mult] (untap-all* mult))

(defprotocol Pub
  (sub* [p v ch close?])
  (unsub* [p v ch])
  (unsub-all* [p] [p v]))

(defsfn pub
  "Creates and returns a pub(lication) of the supplied channel,
   partitioned into topics by the topic-fn. topic-fn will be applied to
   each value on the channel and the result will determine the 'topic'
   on which that value will be put. Channels can be subscribed to
   receive copies of topics using 'sub', and unsubscribed using
   'unsub'. Each topic will be handled by an internal mult on a
   dedicated channel. By default these internal channels are
   unbuffered, but a buf-fn can be supplied which, given a topic,
   creates a buffer with desired properties.

   Each item is distributed to all subs in parallel and synchronously,
   i.e. each sub must accept before the next item is distributed. Use
   buffering/windowing to prevent slow subs from holding up the pub.

   Items received when there are no matching subs get dropped.

   Note that if buf-fns are used then each topic is handled
   asynchronously, i.e. if a channel is subscribed to more than one
   topic it should not expect them to be interleaved identically with
   the source."
  ([ch topic-fn] (pub ch topic-fn (constantly nil)))
  ([ch topic-fn buf-fn]
    (let [selector
            (reify Function
              (apply [_ m] (topic-fn m)))
          mult-sp-fn
            (reify Function
              (apply [_ topic]
                (let [m (mult (chan (buf-fn topic)))
                      sp (muxch* m)]
                  (Pair. m sp))))
          pub (PubSplitSendPort. selector mult-sp-fn)
          p (p/sreify
              Mux
                (muxch* [_] ch)

              Pub
                (sub* [_ topic ch close?]
                      (let [m (.ensure pub topic)]
                        (tap m ch close?)))
                (unsub* [_ topic ch]
                        (when-let [m (.get pub topic)]
                          (untap m ch)))
                (unsub-all* [_] (.reset pub))
                (unsub-all* [_ topic] (.remove pub topic)))]
      (pipe ch pub)
      p)))

(defsfn sub
        "Subscribes a channel to a topic of a pub.

         By default the channel will be closed when the source closes,
         but can be determined by the close? parameter."
        ([p topic ch] (sub p topic ch true))
        ([p topic ch close?] (sub* p topic ch close?)))

(defn unsub
  "Unsubscribes a channel from a topic of a pub"
  [p topic ch]
  (unsub* p topic ch))

(defn unsub-all
  "Unsubscribes all channels from a pub, or a topic of a pub"
  ([p] (unsub-all* p))
  ([p topic] (unsub-all* p topic)))

;;; These are down here because they alias core fns, don't want accidents above.

(defsfn map
  "Takes a function and a collection of source channels, and returns a
   channel which contains the values produced by applying f to the set
   of first items taken from each source channel, followed by applying
   f to the set of second items from each channel, until any one of the
   channels is closed, at which point the output channel will be
   closed. The returned channel will be unbuffered by default, or a
   buf-or-n can be supplied"
  ([f chs] (map f chs nil))
  ([f chs buf-or-n]
    (let [chs (seq chs)
          out (chan buf-or-n)]
      (pipe (Channels/zip chs (reify Function (apply [_ a] (apply f (seq a))))) out)
      out)))

(defsfn into
  "Returns a channel containing the single (collection) result of the
   items taken from the channel conjoined to the supplied
   collection. ch must close before into produces a result."
  [coll ch]
  (reduce conj coll ch))

(p/defsfn ^:private pipeline*
  ([n to xf from close? ex-handler type]
    (assert (pos? n))
    (let [pline
            (p/sfn [n transform ch-builder]
              (p/spawn-fiber
                (p/sfn []
                  (.run
                    (Pipeline. from to transform n (if close? true false) ch-builder)))))
          identity-transform
            (p/sreify SuspendableAction2
              (call [_ v c] (>! c v) (close! c)))
          transforming-transform
            (p/sreify SuspendableAction2
              (call [_ v c] (xf v c)))
          ex-handler (or ex-handler (fn [ex]
                                      (-> (Strand/currentStrand)
                                          .getUncaughtExceptionHandler
                                          (.uncaughtException (Strand/currentStrand) ex))
                                      nil))
          transducing-channel-builder
            (p/sreify SuspendableCallable
              (run [_] (chan 1 xf ex-handler)))
          plain-channel-builder
            (p/sreify SuspendableCallable
              (run [_] (chan 1)))]
      (case type
        :blocking (pline 0 identity-transform transducing-channel-builder)
        :compute (pline n identity-transform transducing-channel-builder)
        :async (pline n transforming-transform plain-channel-builder)))))

;;todo - switch pipe arg order to match these (to/from)
(defn pipeline
  "Takes elements from the from channel and supplies them to the to
   channel, subject to the transducer xf, with parallelism n. Because
   it is parallel, the transducer will be applied independently to each
   element, not across elements, and may produce zero or more outputs
   per input.  Outputs will be returned in order relative to the
   inputs. By default, the to channel will be closed when the from
   channel closes, but can be determined by the close? parameter. Will
   stop consuming the from channel if the to channel closes. Note this
   should be used for computational parallelism. If you have multiple
   blocking operations to put in flight, use pipeline-blocking instead,
   If you have multiple asynchronous operations to put in flight, use
   pipeline-async instead."
  ([n to xf from] (pipeline n to xf from true))
  ([n to xf from close?] (pipeline n to xf from close? nil))
  ([n to xf from close? ex-handler] (pipeline* n to xf from close? ex-handler :compute)))

(defn pipeline-blocking
  "Like pipeline, for blocking operations."
  ([n to xf from] (pipeline-blocking n to xf from true))
  ([n to xf from close?] (pipeline-blocking n to xf from close? nil))
  ([n to xf from close? ex-handler] (pipeline* n to xf from close? ex-handler :blocking)))

(defn pipeline-async
  "Takes elements from the from channel and supplies them to the to
   channel, subject to the async function af, with parallelism n. af
   must be a function of two arguments, the first an input value and
   the second a channel on which to place the result(s). af must close!
   the channel before returning. The presumption is that af will
   return immediately, having launched some asynchronous operation
   (i.e. in another strand) whose completion/callback will manipulate
   the result channel. Outputs will be returned in order relative to
   the inputs. By default, the to channel will be closed when the from
   channel closes, but can be determined by the close?  parameter. Will
   stop consuming the from channel if the to channel closes. See also
   pipeline, pipeline-blocking."
  ([n to af from] (pipeline-async n to af from true))
  ([n to af from close?] (pipeline* n to af from close? nil :async)))
