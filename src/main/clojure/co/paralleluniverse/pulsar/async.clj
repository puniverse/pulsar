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
;
;
; Docstrings and "OPS" functions are derived from core.async (https://github.com/clojure/core.async).
; Copyright (C) 2013 Rich Hickey and contributors.
; Distributed under the Eclipse Public License, the same as Clojure.
;
(ns co.paralleluniverse.pulsar.async
  "Fiber-based implementation of [org.clojure/core.async \"0.1.346.0-17112a-alpha\"]"
  (:refer-clojure :exclude [reduce into merge map take partition  partition-by] :as core)
  (:require
    [co.paralleluniverse.pulsar.core :as p :refer [defsfn sfn]])
  (:import
    [co.paralleluniverse.strands.channels QueueObjectChannel TransferChannel TimeoutChannel Channels$OverflowPolicy SendPort ReceivePort Selector SelectAction Channels]
    [co.paralleluniverse.strands.queues ArrayQueue BoxQueue CircularObjectBuffer]
    [java.util.concurrent TimeUnit Executors Executor]
    [com.google.common.util.concurrent ThreadFactoryBuilder]
    (java.util List Arrays)
    (co.paralleluniverse.strands Strand)
    (com.google.common.base Function)
    (co.paralleluniverse.pulsar DelegatingChannel)))

(alias 'core 'clojure.core)

; TODO port (or run unmodified if possible) original async testsuite

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

(defn rx-chan [chan xform ex-handler]
  "Returns a new transforming channel based on the one passed as a first argument. The given transformation will
  be applied."
  (let [transform-and-handle (if xform
                               (if ex-handler
                                 (fn [val-producer]
                                   (let [val (val-producer)]
                                     (try (xform val)
                                       (catch Throwable t (or (ex-handler t) (throw t))))))
                                   (fn [val-producer] (xform (val-producer))))
                                 (fn [val-producer] (val-producer)))]
     (cond
       (and (nil? xform) (nil? ex-handler))
         chan
       :else
       (DelegatingChannel. chan
                           (.map (Channels/transform chan)
                                 (reify Function
                                   (apply [_ v] (transform-and-handle v))
                                   (equals [this that] (= this that))))
                           chan))))

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
     (nil? buf-or-n)    (rx-chan (TransferChannel.) xform ex-handler)
     :else              (rx-chan (QueueObjectChannel. (first buf-or-n) (second buf-or-n) false) xform ex-handler))))

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
  
   Pulsar implementation: Identical to >!!. May be used outside go blocks as well. "
  [port val]
  (p/snd port val))

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
        (when fn1 (fn1 res))
        (p/spawn-fiber #(let [res (p/snd port val)]
                           (when fn1 (fn1 res)))))
      (do
        (fn1 false)
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

(defn- do-alts-internal
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

(defn do-alts
  "Returns derefable [val port] if immediate, nil if enqueued"
  [fret ports opts]
  (let [^SelectAction sa (do-alts-internal ports opts)
        v [(.message sa) (.port sa)]]
    (if (and (contains? opts :default) (nil? sa))
      [(:default opts) :default]
      (if fret (fret v) v))))

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
    (let [binds (clojure.lang.Var/getThreadBindingFrame)]
      (.execute thread-macro-executor
                (fn []
                  (clojure.lang.Var/resetThreadBindingFrame binds)
                  ((f->chan c f)))))
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
    (p/spawn-fiber (f->chan c (p/suspendable! f)))
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
   buffer space is available. Returns nil.
  
   Pulsar implementation: Identical to <!!. May be used outside go blocks as well."
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

; TODO Port on top of new Quasar primitives

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

(defsfn reduce
  "f should be a function of 2 arguments. Returns a channel containing
   the single result of applying f to init and the first item from the
   channel, then applying f to that result and the 2nd item, etc. If
   the channel closes without yielding items, returns init and f is not
   called. ch must close before reduce produces a result."
  [f init ch]
  (go-loop [ret init]
           (let [v (<! ch)]
             (if (nil? v)
               ret
               (recur (f ret v))))))

(defsfn pipe
  "Takes elements from the from channel and supplies them to the to
   channel. By default, the to channel will be closed when the from
   channel closes, but can be determined by the close?  parameter. Will
   stop consuming the from channel if the to channel closes"
  ([from to] (pipe from to true))
  ([from to close?]
    (go-loop []
             (let [v (<! from)]
               (if (nil? v)
                 (when close? (close! to))
                 (when (>! to v)
                   (recur)))))
    to))

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
      (go-loop []
               (let [v (<! ch)]
                 (if (nil? v)
                   (do (close! tc) (close! fc))
                   (when (>! (if (p v) tc fc) v)
                     (recur)))))
      [tc fc])))

(defsfn ^:private pipeline*
  ([n to xf from close? ex-handler type]
    (assert (pos? n))
    (let [ex-handler (or ex-handler (fn [ex]
                                      (-> (Strand/currentStrand)
                                          .getUncaughtExceptionHandler
                                          (.uncaughtException (Strand/currentStrand) ex))
                                      nil))
          jobs (chan n)
          results (chan n)
          process (sfn [[v p :as job]]
                    (if (nil? job)
                      (do (close! results) nil)
                      (let [res (chan 1 xf ex-handler)]
                        (>! res v)
                        (close! res)
                        (put! p res)
                        true)))
          async (fn [[v p :as job]]
                  (if (nil? job)
                    (do (close! results) nil)
                    (let [res (chan 1)]
                      (xf v res)
                      (put! p res)
                      true)))]
      (dotimes [_ n]
        (case type
          :blocking (fiber
                      (let [job (<! jobs)]
                        (when (process job)
                          (recur))))
          :compute (go-loop []
                            (let [job (<! jobs)]
                              (when (process job)
                                (recur))))
          :async (go-loop []
                          (let [job (<! jobs)]
                            (when (async job)
                              (recur))))))
      (go-loop []
               (let [v (<! from)]
                 (if (nil? v)
                   (close! jobs)
                   (let [p (chan 1)]
                     (>! jobs [v p])
                     (>! results p)
                     (recur)))))
      (go-loop []
               (let [p (<! results)]
                 (if (nil? p)
                   (when close? (close! to))
                   (let [res (<! p)]
                     (loop []
                       (let [v (<! res)]
                         (when (and (not (nil? v)) (>! to v))
                           (recur))))
                     (recur))))))))

;;todo - switch pipe arg order to match these (to/from)
(defsfn pipeline
  "Takes elements from the from channel and supplies them to the to
   channel, subject to the transducer xf, with parallelism n. Because
   it is parallel, the transducer will be applied independently to each
   element, not across elements, and may produce zero or more outputs
   per input.  Outputs will be returned in order relative to the
   inputs. By default, the to channel will be closed when the from
   channel closes, but can be determined by the close?  parameter. Will
   stop consuming the from channel if the to channel closes. Note this
   should be used for computational parallelism. If you have multiple
   blocking operations to put in flight, use pipeline-blocking instead,
   If you have multiple asynchronous operations to put in flight, use
   pipeline-async instead."
  ([n to xf from] (pipeline n to xf from true))
  ([n to xf from close?] (pipeline n to xf from close? nil))
  ([n to xf from close? ex-handler] (pipeline* n to xf from close? ex-handler :compute)))

(defsfn pipeline-blocking
  "Like pipeline, for blocking operations."
  ([n to xf from] (pipeline-blocking n to xf from true))
  ([n to xf from close?] (pipeline-blocking n to xf from close? nil))
  ([n to xf from close? ex-handler] (pipeline* n to xf from close? ex-handler :blocking)))

(defsfn pipeline-async
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

(defprotocol Mux
  (muxch* [_]))

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
  (let [cs (atom {}) ;;ch->close?
        m (reify
            Mux
            (muxch* [_] ch)

            Mult
            (tap* [_ ch close?] (swap! cs assoc ch close?) nil)
            (untap* [_ ch] (swap! cs dissoc ch) nil)
            (untap-all* [_] (reset! cs {}) nil))
        dchan (chan 1)
        dctr (atom nil)
        done (fn [_] (when (zero? (swap! dctr dec))
                       (put! dchan true)))]
    (go-loop []
             (let [val (<! ch)]
               (if (nil? val)
                 (doseq [[c close?] @cs]
                   (when close? (close! c)))
                 (let [chs (keys @cs)]
                   (reset! dctr (count chs))
                   (doseq [c chs]
                     (when-not (put! c val done)
                       (done nil)
                       (untap* m c)))
                   ;;wait for all
                   (when (seq chs)
                     (<! dchan))
                   (recur)))))
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

(defprotocol Mix
  (admix* [m ch])
  (unmix* [m ch])
  (unmix-all* [m])
  (toggle* [m state-map])
  (solo-mode* [m mode]))

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
  (let [cs (atom {}) ;;ch->attrs-map
        solo-modes #{:mute :pause}
        attrs (conj solo-modes :solo)
        solo-mode (atom :mute)
        change (chan)
        changed #(put! change true)
        pick (fn [attr chs]
               (reduce-kv
                 (fn [ret c v]
                   (if (attr v)
                     (conj ret c)
                     ret))
                 #{} chs))
        calc-state (fn []
                     (let [chs @cs
                           mode @solo-mode
                           solos (pick :solo chs)
                           pauses (pick :pause chs)]
                       {:solos solos
                        :mutes (pick :mute chs)
                        :reads (conj
                                 (if (and (= mode :pause) (not (empty? solos)))
                                   (vec solos)
                                   (vec (remove pauses (keys chs))))
                                 change)}))
        m (reify
            Mux
            (muxch* [_] out)
            Mix
            (admix* [_ ch] (swap! cs assoc ch {}) (changed))
            (unmix* [_ ch] (swap! cs dissoc ch) (changed))
            (unmix-all* [_] (reset! cs {}) (changed))
            (toggle* [_ state-map] (swap! cs (partial merge-with core/merge) state-map) (changed))
            (solo-mode* [_ mode]
              (assert (solo-modes mode) (str "mode must be one of: " solo-modes))
              (reset! solo-mode mode)
              (changed)))]
    (go-loop [{:keys [solos mutes reads] :as state} (calc-state)]
             (let [[v c] (alts! reads)]
               (if (or (nil? v) (= c change))
                 (do (when (nil? v)
                       (swap! cs dissoc c))
                     (recur (calc-state)))
                 (if (or (solos c)
                         (and (empty? solos) (not (mutes c))))
                   (when (>! out v)
                     (recur state))
                   (recur state)))))
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
    (let [mults (atom {}) ;;topic->mult
          ensure-mult (sfn [topic]
                        (or (get @mults topic)
                            (get (swap! mults
                                        #(if (% topic) % (assoc % topic (mult (chan (buf-fn topic))))))
                                 topic)))
          p (reify
              Mux
              (muxch* [_] ch)

              Pub
              (sub* [p topic ch close?]
                (let [m (ensure-mult topic)]
                  (tap m ch close?)))
              (unsub* [p topic ch]
                (when-let [m (get @mults topic)]
                  (untap m ch)))
              (unsub-all* [_] (reset! mults {}))
              (unsub-all* [_ topic] (swap! mults dissoc topic)))]
      (go-loop []
               (let [val (<! ch)]
                 (if (nil? val)
                   (doseq [m (vals @mults)]
                     (close! (muxch* m)))
                   (let [topic (topic-fn val)
                         m (get @mults topic)]
                     (when m
                       (when-not (>! (muxch* m) val)
                         (swap! mults dissoc topic)))
                     (recur)))))
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
    (let [chs (vec chs)
          out (chan buf-or-n)
          cnt (count chs)
          rets (object-array cnt)
          dchan (chan 1)
          dctr (atom nil)
          done (mapv (fn [i]
                       (fn [ret]
                         (aset rets i ret)
                         (when (zero? (swap! dctr dec))
                           (put! dchan (Arrays/copyOf rets cnt)))))
                     (range cnt))]
      (go-loop []
               (reset! dctr cnt)
               (dotimes [i cnt]
                 (try
                   (take! (chs i) (done i))
                   (catch Exception e
                     (swap! dctr dec))))
               (let [rets (<! dchan)]
                 (if (some nil? rets)
                   (close! out)
                   (do (>! out (apply f rets))
                       (recur)))))
      out)))

(defsfn merge
  "Takes a collection of source channels and returns a channel which
   contains all values taken from them. The returned channel will be
   unbuffered by default, or a buf-or-n can be supplied. The channel
   will close after all the source channels have closed."
  ([chs] (merge chs nil))
  ([chs buf-or-n]
    (let [out (chan buf-or-n)]
      (go-loop [cs (vec chs)]
               (if (pos? (count cs))
                 (let [[v c] (alts! cs)]
                   (if (nil? v)
                     (recur (filterv #(not= c %) cs))
                     (do (>! out v)
                         (recur cs))))
                 (close! out)))
      out)))

(defsfn into
  "Returns a channel containing the single (collection) result of the
   items taken from the channel conjoined to the supplied
   collection. ch must close before into produces a result."
  [coll ch]
  (reduce conj coll ch))

(defsfn take
  "Returns a channel that will return, at most, n items from ch. After n items
   have been returned, or ch has been closed, the return channel will close.

   The output channel is unbuffered by default, unless buf-or-n is given."
  ([n ch]
    (take n ch nil))
  ([n ch buf-or-n]
    (let [out (chan buf-or-n)]
      (go (loop [x 0]
            (when (< x n)
              (let [v (<! ch)]
                (when (not (nil? v))
                  (>! out v)
                  (recur (inc x))))))
          (close! out))
      out)))