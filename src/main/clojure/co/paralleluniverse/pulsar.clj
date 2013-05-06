;;;
;;;
;;;
;;;

(ns co.paralleluniverse.pulsar
  "Pulsar is an implementation of lightweight threads (fibers),
  Go-like channles and Erlang-like actors for the JVM"
  (:import [java.util.concurrent TimeUnit]
           [jsr166e ForkJoinPool ForkJoinTask]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.strands SuspendableCallable]
           [co.paralleluniverse.fibers Fiber Joinable FiberInterruptedException]
           [co.paralleluniverse.fibers.instrument]
           [co.paralleluniverse.strands.channels Channel ObjectChannel IntChannel LongChannel FloatChannel DoubleChannel]
           [co.paralleluniverse.actors ActorRegistry Actor PulsarActor]
           [co.paralleluniverse.pulsar ClojureHelper])
  (:use [clojure.core.match :only [match]]))


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

(defn- sequentialize
  "Takes a function of a single argument and returns a function that either takes any number of arguments or a
  a single sequence, and applies the original function to each argument or each element of the sequence"
  [f]
  (fn
    ([x] (if (sequential? x) (map f x) (f x)))
    ([x & xs] (map f (cons x xs)))))

(defn- nth-from-last
  ([coll index]
   (nth coll (- (dec (count coll)) index)))
  ([coll index not-found]
   (nth coll (- (dec (count coll)) index) not-found)))

(defn- split-at-from-last
  [index coll]
  (split-at (- (dec (count coll)) index) coll))

;; (surround-with nil 4 5 6) -> (4 5 6)
;; (surround-with '(1 2 3) 4 5 6) -> ((1 2 3 4 5 6))
;; (surround-with '(1 (2)) '(3 4)) -> ((1 (2) (3 4)))
(defn surround-with
  [expr & exprs]
  (if (nil? expr)
    exprs
    (list (concat expr exprs))))

;; (deep-surround-with '(1 2 3) 4 5 6) -> (1 2 3 4 5 6)
;; (deep-surround-with '(1 2 (3)) 4 5 6) -> (1 2 (3 4 5 6))
;; (deep-surround-with '(1 2 (3 (4))) 5 6 7) -> (1 2 (3 (4 5 6 7)))
(defn- deep-surround-with
  [expr & exprs]
  (if (not (coll? (last expr)))
    (concat expr exprs)
    (concat (butlast expr) (list (apply deep-surround-with (cons (last expr) exprs))))))

(defn- ops-args
  [pds xs]
  "Used to simplify optional parameters in functions.
  Takes a sequence of [predicate? default] pairs, and a sequence of arguments. Tests the first predicate against
  the first argument. If the predicate succeeds, emits the argument's value; if not - the default, and tries the
  next pair with the argument. Any remaining arguments are copied to the output as-is."
  (if (seq pds)
    (let [[p? d] (first pds)
          x      (first xs)]
      (if (p? x)
        (cons x (ops-args (rest pds) (rest xs)))
        (cons d (ops-args (rest pds) xs))))
    (seq xs)))

(defn- kps-args
  [args]
  (let [aps (partition-all 2 args)
        [opts-and-vals ps] (split-with #(keyword? (first %)) aps)
        options (into {} (map vec opts-and-vals))
        positionals (reduce into [] ps)]
    [options positionals]))

(defn- extract-keys
  [ks pargs]
  (if (not (seq ks))
    [[] pargs]
    (let [[k ps] (split-with #(= (first ks) (first %)) pargs)
          [rks rpargs] (extract-keys (next ks) ps)]
      [(vec (cons (first k) rks)) rpargs])))

(defn merge-meta
  {:no-doc true}
  [s m]
  (with-meta s (merge-with #(%1) m (meta s))))

(defn as-timeunit
  "Converts a keyword to a java.util.concurrent.TimeUnit
  <pre>
  :nanoseconds | :nanos         -> TimeUnit.NANOSECONDS
  :microseconds | :us           -> TimeUnit.MICROSECONDS
  :milliseconds | :millis | :ms -> TimeUnit.MILLISECONDS
  :seconds | :sec               -> TimeUnit.SECONDS
  :minutes | :mins              -> TimeUnit.MINUTES
  :hours | :hrs                 -> TimeUnit.HOURS
  :days                         -> TimeUnit.DAYS
  </pre>
  "
  [x]
  (if (keyword? x)
    (ClojureHelper/keywordToUnit x)
    x))

;; ## fork/join pool

(defn- in-fj-pool?
  "Returns true if we're running inside a fork/join pool; false otherwise."
  []
  (ForkJoinTask/inForkJoinPool))

(defn- current-fj-pool
  "Returns the fork/join pool we're running in; nil if we're not in a fork/join pool."
  []
  (ForkJoinTask/getPool))

(defn make-fj-pool
  "Creates a new ForkJoinPool with the given parallelism and with the given async mode"
  [^Integer parallelism ^Boolean async]
  (ForkJoinPool. parallelism jsr166e.ForkJoinPool/defaultForkJoinWorkerThreadFactory nil async))

(def fj-pool
  "A global fork/join pool. The pool uses all available processors and runs in the async mode."
  (make-fj-pool (.availableProcessors (Runtime/getRuntime)) true))

;; ***Make agents use the global fork-join pool***

(set-agent-send-executor! fj-pool)
(set-agent-send-off-executor! fj-pool)

;; ## Suspendable functions
;; Only functions that have been especially instrumented can perform blocking actions
;; while running in a fiber.

(defn suspendable?
  "Returns true of a function has been instrumented as suspendable; false otherwise."
  [f]
  (.isAnnotationPresent (.getClass ^Object f) co.paralleluniverse.fibers.Instrumented))

(def suspendable!
  "Makes a function suspendable"
  (sequentialize
   (fn [f]
     (ClojureHelper/retransform f))))

(defn ^SuspendableCallable asSuspendableCallable
  "wrap a clojure function as a SuspendableCallable"
  {:no-doc true}
  [f]
  (ClojureHelper/asSuspendableCallable f))


(defmacro susfn
  "Creates a suspendable function that can be used by a fiber or actor"
  [& expr]
  `(suspendable! (fn ~@expr)))

(defmacro defsusfn
  "Defines a suspendable function that can be used by a fiber or actor"
  [& expr]
  `(do
     (defn ~@expr)
     (suspendable! ~(first expr))))

;; ## Fibers

(defn ^ForkJoinPool get-pool
  {:no-doc true}
  [^ForkJoinPool pool]
  (or pool (current-fj-pool) fj-pool))

(defn ^Fiber fiber1
  "Creates a new fiber (a lightweight thread) running in a fork/join pool."
  {:no-doc true}
  [^String name ^ForkJoinPool pool ^Integer stacksize ^SuspendableCallable target]
  (Fiber. name (get-pool pool) (int stacksize) target))

(defn ^Fiber fiber
  "Creates a new fiber (a lightweight thread) running in a fork/join pool."
  [& args]
  (let [[^String name ^ForkJoinPool pool ^Integer stacksize f] (ops-args [[string? nil] [#(instance? ForkJoinPool %) fj-pool] [integer? -1]] args)]
    (Fiber. name (get-pool pool) (int stacksize) (asSuspendableCallable f))))

(defn start
  "Starts a fiber"
  [^Fiber fiber]
  (.start fiber))

(defmacro spawn-fiber
  "Creates and starts a new fiber"
  [& args]
  (let [[{:keys [^String name ^Integer stack-size ^ForkJoinPool pool], :or {stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! ~(if (== (count body) 1) (first body) `(fn [] (apply ~@body))))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) (asSuspendableCallable f#))]
       (.start fiber#))))

(defn current-fiber
  "Returns the currently running lightweight-thread or nil if none"
  []
  (Fiber/currentFiber))

(defn join
  ([^Joinable s]
   (.get s))
  ([^Joinable s timeout ^TimeUnit unit]
   (.get s timeout unit)))

;; ## Strands
;; A strand is either a thread or a fiber.

(defn ^Strand current-strand
  "Returns the currently running fiber or current thread in case of new active fiber"
  []
  (Strand/currentStrand))


;; ## Channels

(defn attach!
  "Sets a channel's owning strand (fiber or thread).
  This is done automatically the first time a rcv (or one of the primitive-type receive-xxx) is called on the channel."
  [^Channel channel strand]
  (.setStrand channel strand))

(defn channel
  "Creates a channel"
  ([size] (ObjectChannel/create size))
  ([] (ObjectChannel/create -1)))

(defn snd
  "Sends a message to a channel"
  [^Channel channel message]
  (.send channel message))

(defsusfn rcv
  "Receives a message from a channel"
  ([^Channel channel]
   (.receive channel))
  ([^Channel channel timeout unit]
   (.receive channel (long timeout) unit)))

(defsusfn rcv-seq
  "Turns a channel into a lazy-seq"
  ([^Channel channel]
   (when-let [m (.receive channel)]
     (lazy-seq
      (cons m (rcv-seq channel)))))
  ([^Channel channel timeout unit]
   (when-let [m (.receive channel (long timeout) unit)]
     (lazy-seq
      (cons m (rcv-seq channel timeout unit))))))

(defn snd-seq
  "Sends a sequence of messages to a channel"
  [^Channel channel ms]
  (doseq [m ms]
    (.send channel m)))

;; ### Primitive channels

(defn ^IntChannel int-channel
  "Creates an int channel"
  ([size] (IntChannel/create size))
  ([] (IntChannel/create -1)))

(defmacro send-int
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendInt ~channel (int ~message)))

(defmacro receive-int
  ([channel]
   `(int (co.paralleluniverse.pulsar.ChannelsHelper/receiveInt ~channel)))
  ([channel timeout unit]
   `(int (co.paralleluniverse.pulsar.ChannelsHelper/receiveInt ~channel (long ~timeout) ~unit))))

(defn ^LongChannel long-channel
  "Creates a long channel"
  ([size] (LongChannel/create size))
  ([] (LongChannel/create -1)))

(defmacro send-long
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendLong ~channel (long ~message)))

(defmacro receive-long
  ([channel]
   `(long (co.paralleluniverse.pulsar.ChannelsHelper/receiveLong ~channel)))
  ([channel timeout unit]
   `(long (co.paralleluniverse.pulsar.ChannelsHelper/receiveLong ~channel (long ~timeout) ~unit))))

(defn ^FloatChannel float-channel
  "Creates a float channel"
  ([size] (FloatChannel/create size))
  ([] (FloatChannel/create -1)))

(defmacro send-float
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendLong ~channel (float ~message)))

(defmacro receive-float
  ([channel]
   `(float (co.paralleluniverse.pulsar.ChannelsHelper/receiveFloat ~channel)))
  ([channel timeout unit]
   `(float (co.paralleluniverse.pulsar.ChannelsHelper/receiveFloat ~channel (long ~timeout) ~unit))))

(defn ^DoubleChannel double-channel
  "Creates a double channel"
  ([size] (DoubleChannel/create size))
  ([] (DoubleChannel/create -1)))

(defmacro send-double
  [channel message]
  `(co.paralleluniverse.pulsar.ChannelsHelper/sendLong ~channel (double ~message)))

(defmacro receive-double
  ([channel]
   `(double (co.paralleluniverse.pulsar.ChannelsHelper/receiveDouble ~channel)))
  ([channel timeout unit]
   `(double (co.paralleluniverse.pulsar.ChannelsHelper/receiveDouble ~channel (long ~timeout) ~unit))))


;; ## Actors

(defmacro actor
  "Creates a new actor."
  {:arglists '([bindings & body])}
  [bs & body]
  (assert-args
   (vector? bs) "a vector for its binding"
   (even? (count bs)) "an even number of forms in binding vector")
  `(suspendable!
    ~(if (> (count bs) 0)
       ; actor with state fields
       (let [type (gensym "actor")
             fs (vec (take-nth 2 bs)) ; field names
             ivs (take-nth 2 (next bs))] ; initial values
         (eval ; this runs at compile time!
          (let [fs (vec (map #(merge-meta % {:unsynchronized-mutable true}) fs))]
            `(deftype ~type ~fs
               clojure.lang.IFn
               (invoke [this#] ~@body)
               (applyTo [this# args#] (clojure.lang.AFn/applyToHelper this# args#)))))
         `(new ~type ~@ivs))
       ; regular actor
       `(fn [] ~@body))))

(defmacro defactor
  "Defines a new actor template."
  {:arglists '([name doc-string? attr-map? [params*] body])}
  [n & decl]
  (let [decl1 decl
        decl1 (if (string? (first decl1)) (next decl1) decl1) ; strip docstring
        decl1 (if (map? (first decl1)) (next decl1) decl1)    ; strip meta
        fs (first decl1)]
    (assert-args
     (vector? fs) "a vector for its binding")
    (if (> (count fs) 0)
      ; actor with state fields
      (let [fs1 (vec (map #(merge-meta % {:unsynchronized-mutable true}) fs))
            body (next decl1)
            type (symbol (str (name n) "_type"))
            arity (count fs)
            args  (repeatedly arity gensym)]
        `(do
           (deftype ~type ~fs1
             clojure.lang.IFn
             (invoke [this#] ~@body)
             (applyTo [this# args#] (clojure.lang.AFn/applyToHelper this# args#)))
           (suspendable! ~type)
           (defn ~n [~@args]
             ((new ~type ~@args)))
           (suspendable! ~n)))
      ; regular actor
      `(do
         (defn ~n ~@decl)
         (suspendable! ~n)))))

(defmacro spawn
  "Creates and starts a new actor"
  {:arglists '([:name? :mailbox-size? :lifecycle-handler? :stack-size? :pool? f & args])}
  [& args]
  (let [[{:keys [^String name ^Boolean trap ^Integer mailbox-size ^IFn lifecycle-handler ^Integer stack-size ^ForkJoinPool pool], :or {trap false mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! ~(if (== (count body) 1) (first body) `(fn [] (apply ~(first body) (list ~@(rest body))))))
           actor# (co.paralleluniverse.actors.PulsarActor. ~name ~trap (int ~mailbox-size) ~lifecycle-handler f#)
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) actor#)]
       (.start fiber#)
       actor#)))

(defmacro spawn-link
  "Creates and starts a new actor, and links it to @self"
  {:arglists '([:name? :mailbox-size? :lifecycle-handler? :stack-size? :pool? f & args])}
  [& args]
  (let [[{:keys [^String name ^Boolean trap ^Integer mailbox-size ^IFn lifecycle-handler ^Integer stack-size ^ForkJoinPool pool], :or {trap false mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! ~(if (== (count body) 1) (first body) `(fn [] (apply ~(first body) (list ~@(rest body))))))
           actor# (co.paralleluniverse.actors.PulsarActor. ~name ~trap (int ~mailbox-size) ~lifecycle-handler f#)
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) actor#)]
       (link! @self actor#)
       (.start fiber#)
       actor#)))

(defmacro spawn-monitor
  "Creates and starts a new actor, and makes @self monitor it"
  {:arglists '([:name? :mailbox-size? :lifecycle-handler? :stack-size? :pool? f & args])}
  [& args]
  (let [[{:keys [^String name ^Boolean trap ^Integer mailbox-size ^IFn lifecycle-handler ^Integer stack-size ^ForkJoinPool pool], :or {trap false mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! ~(if (== (count body) 1) (first body) `(fn [] (apply ~(first body) (list ~@(rest body))))))
           actor# (co.paralleluniverse.actors.PulsarActor. ~name ~trap (int ~mailbox-size) ~lifecycle-handler f#)
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) actor#)]
       (monitor! @self actor#)
       (.start fiber#)
       actor#)))

(def self
  "@self is the currently running actor"
  (reify
    clojure.lang.IDeref
    (deref [_] (PulsarActor/self))))

(def state
  "@state is the state of the currently running actor"
  (reify
    clojure.lang.IDeref
    (deref [_] (PulsarActor/selfGetState))))

(def mailbox
  "@mailbox is the mailbox channel of the currently running actor"
  (reify
    clojure.lang.IDeref
    (deref [_] (PulsarActor/selfMailbox))))

(defn set-state!
  "Sets the state of the currently running actor"
  [x]
  (PulsarActor/selfSetState x))

(defn trap!
  "Sets the current actor to trap lifecycle events (like a dead linked actor) and turn them into messages"
  []
  (.setTrap ^PulsarActor @self true))

(defn ^Actor get-actor
  "If the argument is an actor -- returns it. If not, looks up a registered actor with the argument as its name"
  [a]
  (if (instance? Actor a)
    a
    (Actor/getActor a)))

(defn link!
  "links two actors"
  ([actor2]
   (.link ^Actor @self actor2))
  ([actor1 actor2]
   (.link (get-actor actor1) (get-actor actor2))))

(defn unlink!
  "Unlinks two actors"
  ([actor2]
   (.unlink ^Actor @self actor2))
  ([actor1 actor2]
   (.unlink (get-actor actor1) (get-actor actor2))))

(defn monitor!
  "Makes an actor monitor another actor. Returns a monitor object which should be used when calling demonitor."
  ([actor2]
   (.monitor ^Actor @self actor2))
  ([actor1 actor2]
   (.monitor (get-actor actor1) (get-actor actor2))))

(defn demonitor!
  "Makes an actor stop monitoring another actor"
  ([actor2 monitor]
   (.demonitor ^Actor @self actor2 monitor))
  ([actor1 actor2 monitor]
   (.demonitor (get-actor actor1) (get-actor actor2) monitor)))

(defn register
  "Registers an actor"
  ([name ^Actor actor]
   (.register actor name)
   actor)
  ([^Actor actor]
   (.register actor)
   actor))

(defn unregister
  "Un-registers an actor"
  ([x]
   (if (instance? Actor x)
     (let [^Actor actor x]
       (.unregister actor))
     (ActorRegistry/unregister x)))
  ([^Actor actor name]
   (.unregister actor name)))

(defn ^Channel mailbox-of
  [^PulsarActor actor]
  (.mailbox actor))

(defn ^Actor whereis
  "Returns a registered actor by name"
  [name]
  (Actor/getActor name))

(defn maketag
  "Returns a random, probably unique, identifier.
  (this is similar to Erlang's makeref)."
  []
  (Actor/randtag))

(defmacro !
  "Sends a message to an actor.
  This function returns nil."
  ([actor message]
   `(co.paralleluniverse.actors.PulsarActor/send (get-actor ~actor) ~message))
  ([actor arg & args]
   `(co.paralleluniverse.actors.PulsarActor/send (get-actor ~actor) [~arg ~@args])))

(defmacro !!
  "Sends a message to an actor synchronously.
  This has the exact same semantics as ! (in particular, this function always returns nil),
  but it hints the scheduler that the current actor is about to wait for a response from the message's addressee."
  ([actor message]
   `(co.paralleluniverse.actors.PulsarActor/sendSync (get-actor ~actor) ~message))
  ([actor arg & args]
   `(co.paralleluniverse.actors.PulsarActor/sendSync (get-actor ~actor) [~arg ~@args])))

(defsusfn receive-timed
  "Waits (and returns) for a message for up to timeout ms. If time elapses -- returns nil."
  [^Integer timeout]
  (co.paralleluniverse.actors.PulsarActor/selfReceive timeout))

;; For examples of this macro's expansions, try:
;; (pprint (macroexpand-1 '(receive)))
;; (pprint (macroexpand-1 '(receive [:a] :hi :else :bye)))
;; (pprint (macroexpand-1 '(receive [msg] [:a] :hi :else :bye)))
;; (pprint (macroexpand-1 '(receive [msg #(* % %)] [:a] :hi :else :bye)))
;;
;; (pprint (macroexpand-1 '(receive :else m :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [m] :else m :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [:a] :hi :else :bye :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [msg] [:a] :hi :else :bye :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [msg #(* % %)] [:a] :hi :else :bye :after 30 :foo)))
;;
;; (pprint (macroexpand-1 '(receive [:a x] [:hi x] [:b x] [:bye x])))
;; (pprint (macroexpand-1 '(receive [:a x] [:hi x] [:b x] [:bye x] :after 30 :foo)))

(defmacro receive
  "Receives a message in the current actor and processes it"
  {:arglists '([]
               [patterns* <:after ms action>?]
               [[binding transformation?] patterns* <:after ms action>?])}
  ([]
   `(co.paralleluniverse.actors.PulsarActor/selfReceive))
  ([& body]
   (assert-args
    (or (even? (count body)) (vector? (first body))) "a vector for its binding")
   (let [[body after-clause] (if (= :after (nth-from-last body 2 nil)) (split-at-from-last 2 body) [body nil])
         odd-forms   (odd? (count body))
         bind-clause (if odd-forms (first body) nil)
         transform   (second bind-clause)
         body        (if odd-forms (next body) body)
         m           (if bind-clause (first bind-clause) (gensym "m"))
         timeout     (gensym "timeout")]
     (if (seq (filter #(= % :else) (take-nth 2 body)))
       ; if we have an :else then every message is processed and our job is easy
       `(let ~(into [] (concat
                        (if after-clause `[~timeout ~(second after-clause)] [])
                        `[~m ~(concat `(co.paralleluniverse.actors.PulsarActor/selfReceive) (if after-clause `(~timeout) ()))]
                        (if transform `[~m (~transform ~m)] [])))
          ~@(surround-with (when after-clause `(if (nil? ~m) ~(nth after-clause 2)))
                           `(match ~m ~@body)))
       ; if we don't, well, we have our work cut out for us
       (let [pbody   (partition 2 body)
             mailbox (gensym "mailbox") n (gensym "n") m2 (gensym "m2") mtc (gensym "mtc") exp (gensym "exp")] ; symbols
         `(let [[~mtc ~m]
                (let ~(into [] (concat `[^co.paralleluniverse.actors.PulsarActor ~mailbox (co.paralleluniverse.actors.PulsarActor/self)]
                                       (if after-clause `[~timeout ~(second after-clause)
                                                          ~exp (if (> ~timeout 0) (long (+ (long (System/nanoTime)) (long (* 1000000 ~timeout)))) 0)] [])))
                  (.maybeSetCurrentStrandAsOwner ~mailbox)
                  (loop [prev# nil]
                    (.lock ~mailbox)
                    (let [~n (.succ ~mailbox prev#)]
                      ~(let [quick-match (concat ; ((pat1 act1) (pat2 act2)...) => (pat1 (do (.processed mailbox# n#) 0) pat2 (do (del mailbox# n#) 1)... :else -1)
                                          (mapcat #(list (first %1) `(do (.processed ~mailbox ~n) ~%2)) pbody (range)); for each match pattern, call processed and return an ordinal
                                          `(:else (do (.skipped ~mailbox ~n) -1)))]
                         `(if ~n
                            (do
                              (.unlock ~mailbox)
                              (let [m1# (.value ~mailbox ~n)]
                                (when (and (not (.isTrap ~mailbox)) (instance? co.paralleluniverse.actors.LifecycleMessage m1#))
                                  (.handleLifecycleMessage ~mailbox m1#))
                                (let [~m2 (co.paralleluniverse.actors.PulsarActor/convert m1#)
                                      ~m ~(if transform `(~transform ~m2) `~m2)
                                      act# (int (match ~m ~@quick-match))]
                                  (if (>= act# 0)
                                    [act# ~m]     ; we've got a match!
                                    (recur ~n))))) ; no match. try the next
                            ; ~n == nil
                            ~(if after-clause
                               `(when-not (== ~timeout 0)
                                  (do ; timeout != 0 and ~n == nil
                                    (try
                                      (.await ~mailbox (- ~exp (long (System/nanoTime))) java.util.concurrent.TimeUnit/NANOSECONDS)
                                      (finally
                                       (.unlock ~mailbox)))
                                    (when-not (> (long (System/nanoTime)) ~exp)
                                      (recur ~n))))
                               `(do
                                  (try
                                    (.await ~mailbox)
                                    (finally
                                     (.unlock ~mailbox)))
                                  (recur ~n))))))))]
            ~@(surround-with (when after-clause `(if (nil? ~mtc) ~(nth after-clause 2)))
                             ; now, mtc# is the number of the matching clause and m# is the message. we could have used a simple (case) to match on mtc#,
                             ; but the patterns might have wildcards so we'll match again (to get the bindings), but we'll help the match by mathing on mtc#
                             `(match [~mtc ~m] ~@(mapcat #(list [%2 (first %1)] (second %1)) pbody (range))))))))))


