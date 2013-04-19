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
           [co.paralleluniverse.actors Actor PulsarActor]
           [co.paralleluniverse.pulsar ClojureHelper])
  (:use [clojure.core.match :only [match]]))


;; ## Private util functions
;; These are internal functions aided to assist other functions in handling variadic arguments and the like.

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

(defn- sequentialize
  "Takes a function of a single argument and returns a function that either takes any number of arguments or a
  a single sequence, and applies the original function to each argument or each element of the sequence"
  [f]
  (fn
    ([x] (if (sequential? x) (map f x) (f x)))
    ([x & xs] (map f (cons x xs)))))

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
  `(do (defn ~@expr)
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
    `(let [f# (suspendable! (fn [] ~@body))
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
  (co.paralleluniverse.pulsar.ChannelsHelper/send channel message))

(defn rcv
  "Receives a message from a channel"
  ([^Channel channel]
   (co.paralleluniverse.pulsar.ChannelsHelper/receive channel))
  ([channel timeout unit]
   (co.paralleluniverse.pulsar.ChannelsHelper/receive channel (long timeout) unit)))

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

(defn ^PulsarActor actor
  "Creates a new actor."
  ([^String name ^Integer mailbox-size f]
   (PulsarActor. name (int mailbox-size) (asSuspendableCallable f)))
  ([f]
   (PulsarActor. nil -1 (asSuspendableCallable f)))
  ([arg1 arg2]
   (let [[^String name ^Integer mailbox-size f] (ops-args [[string? nil] [integer? -1]] [arg1 arg2])]
     (PulsarActor. name (int mailbox-size) (asSuspendableCallable f)))))

(def self
  "@self is the currently running actor"
  (reify 
    clojure.lang.IDeref
    (deref [_] (Actor/currentActor))))

(defmacro spawn
  "Creates and starts a new actor"
  [& args]
  (let [[{:keys [^String name ^Integer mailbox-size ^Integer stack-size ^ForkJoinPool pool], :or {mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! (fn [] ~@body))
           actor# (co.paralleluniverse.actors.PulsarActor. ~name (int ~mailbox-size) (asSuspendableCallable f#))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) actor#)]
       (.start fiber#)
       actor#)))

(defmacro spawn-link
  "Creates and starts a new actor, and links it to @self"
  [& args]
  (let [[{:keys [^String name ^Integer mailbox-size ^Integer stack-size ^ForkJoinPool pool], :or {mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! (fn [] ~@body))
           actor# (co.paralleluniverse.actors.PulsarActor. ~name (int ~mailbox-size) (asSuspendableCallable f#))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) actor#)]
       (link! @self actor#)
       (start fiber#)
       actor#)))

(defmacro spawn-monitor
  "Creates and starts a new actor, and makes @self monitor it"
  [& args]
  (let [[{:keys [^String name ^Integer mailbox-size ^Integer stack-size ^ForkJoinPool pool], :or {mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [f#     (suspendable! (fn [] ~@body))
           actor# (co.paralleluniverse.actors.PulsarActor. ~name (int ~mailbox-size) (asSuspendableCallable f#))
           fiber# (co.paralleluniverse.fibers.Fiber. ~name (get-pool ~pool) (int ~stack-size) actor#)]
       (monitor! @self actor#)
       (start fiber#)
       actor#)))

(defn link!
  "links two actors"
  [^Actor actor1 ^Actor actor2]
  (.link actor1 actor2))

(defn unlink!
  "Unlinks two actors"
  [^Actor actor1 ^Actor actor2]
  (.unlink actor1 actor2))

(defn monitor!
  "Makes an actor monitor another actor. Returns a monitor object which should be used when calling demonitor."
  [^Actor actor1 ^Actor actor2]
  (.monitor actor1 actor2))

(defn demonitor!
  "Makes an actor stop monitoring another actor"
  [^Actor actor1 ^Actor actor2 monitor]
  (.demonitor actor1 actor2 monitor))

(defn register
  "Registers an actor"
  ([^Actor actor ^String name]
   (.register actor name))
  ([^Actor actor]
   (.register actor)))

(defn unregister
  "Un-registers an actor"
  [x]
  (if (instance? Actor x)
    (let [^Actor actor x]
      (.unregister actor))
    (Actor/unregister x)))

(defn ^Actor whereis
  "Returns a registered actor by name"
  [name]
  (Actor/getActor name))

(defmacro !
  "Sends a message to an actor"
  ([actor message]
   `(co.paralleluniverse.actors.PulsarActor/send ~actor ~message))
  ([actor arg & args]
   `(co.paralleluniverse.actors.PulsarActor/send ~actor [~arg ~@args])))

(defmacro !!
  "Sends a message to an actor synchronously"
  ([actor message]
   `(co.paralleluniverse.actors.PulsarActor/sendSync ~actor ~message))
  ([actor arg & args]
   `(co.paralleluniverse.actors.PulsarActor/sendSync ~actor [~arg ~@args])))


;; For examples of this macro's expansions, try:
;; (macroexpand-1 '(receive))
;; (macroexpand-1 '(receive [:a] :hi :else :bye))
;; (macroexpand-1 '(receive [:a x] [:hi x] [:b x] [:bye x]))

(defmacro receive
  "Receives a message in the current actor and processes it"
  ([]
   `(co.paralleluniverse.actors.PulsarActor/selfReceive))
  ([& body]
   (if (seq (filter #(= % :else) (take-nth 2 body))) 
     ; if we have an :else then every message is processed and our job is easy
     `(let [m# (co.paralleluniverse.actors.PulsarActor/selfReceiveAll)] 
        (match m# ~@body))
     ; if we don't, well, we have our work cut out for us
     (let [pbody (partition 2 body)
           ; symbols:
           mailbox (gensym "mailbox")
           n (gensym "n")]
       `(let [[mtc# m#]
              (let [^co.paralleluniverse.strands.channels.Mailbox ~mailbox (co.paralleluniverse.actors.PulsarActor/selfMailbox)]
                (loop [prev# nil]
                  (.lock ~mailbox)
                  (let [~n (.succ ~mailbox prev#)]
                    ; ((pat1 act1) (pat2 act2)...) => (pat1 (do (.del mailbox# n#) 0) pat2 (do (del mailbox# n#) 1)... :else -1)
                    ~(let [quick-match (concat
                                        (mapcat #(list (first %1) ; the match pattern
                                                       `(do (.del ~mailbox ~n) 
                                                          ~%2))
                                                pbody (range))
                                        `(:else -1))]
                       `(if (not (nil? ~n))
                          (let [m# (co.paralleluniverse.actors.PulsarActor/convert (.value ~mailbox ~n))]
                            (println "DDDD " m#)
                            (.unlock ~mailbox)
                            (let [act# (int (match m# ~@quick-match))]
                              (if (>= 0 act#)
                                [act# m#]; we've got a match!
                                (recur ~n)))) ; no match. try the next 
                          (do
                            (println "PPPPP")
                            (try
                              (.await ~mailbox)
                              (finally
                               (.unlock ~mailbox)))
                            (recur ~n)))))))]
          ; now, mtc# is the number of the matching clause and m# is the message. 
          ; we'll match again (to get the bindings)
          (match [mtc# m#] ~@(mapcat #(list [%2 (first %1)] (second %1)); we help the second match by matching on the number
                                     pbody (range))))))))

;; For examples of this macro's expansions, try:
;; (macroexpand-1 '(receive-timed 300))
;; (macroexpand-1 '(receive-timed 300 [:a] :hi :else :bye))
;; (macroexpand-1 '(receive-timed 300 [:a x] [:hi x] [:b x] [:bye x]))

(defmacro receive-timed
  ([timeout]
   `(co.paralleluniverse.actors.PulsarActor/selfReceive (long ~timeout)))
  ([timeout & body]
   (if (seq (filter #(= % :else) (take-nth 2 body))) 
     ; if we have an :else then every message is processed and our job is easy
     `(let [m# (co.paralleluniverse.actors.PulsarActor/selfReceiveAll (long ~timeout))] 
        (if (nil? m#)
          (throw (co.paralleluniverse.fibers.TimeoutException.))
          (match m# ~@body)))
     ; if we don't, well, we have our work cut out for us
     (let [pbody (partition 2 body)
           ; symbols:
           mailbox (gensym "mailbox")
           n (gensym "n")
           exp (gensym "exp")]
       `(let [[mtc# m#]
              (let [^co.paralleluniverse.strands.channels.Mailbox ~mailbox (co.paralleluniverse.actors.PulsarActor/selfMailbox)
                    ~exp (long (+ (long (System/nanoTime)) (long (* 1000000 ~timeout))))]
                (loop [prev# nil]
                  (if (> (long (System/nanoTime)) ~exp)
                    (throw (co.paralleluniverse.fibers.TimeoutException.))
                    
                    (.lock ~mailbox)
                    (let [~n (.succ ~mailbox prev#)]
                      ; ((pat1 act1) (pat2 act2)...) => (pat1 (do (.del mailbox# n#) 0) pat2 (do (del mailbox# n#) 1)... :else -1)
                      ~(let [quick-match (concat
                                          (mapcat #(list (first %1) ; the match pattern
                                                         `(do (.del ~mailbox ~n) 
                                                            ~%2))
                                                  pbody (range))
                                          `(:else -1))]
                       `(if (not (nil? ~n))
                          (let [m# (co.paralleluniverse.actors.PulsarActor/convert (.value ~mailbox ~n))]
                            (.unlock ~mailbox)
                            (let [act# (int (match m# ~@quick-match))]
                              (if (>= 0 act#)
                                [act# m#]; we've got a match!
                                (recur ~n)))) ; no match. try the next 
                            (do
                              (try
                                (.await ~mailbox (- ~exp (long (System/nanoTime))) java.util.concurrent.TimeUnit/NANOSECONDS)
                                (finally
                                 (.unlock ~mailbox)))
                              (recur ~n))))))))]
          ; now, mtc# is the number of the matching clause and m# is the message. 
          ; we'll match again (to get the bindings), but only this clause
          (match [mtc# m#] ~@(mapcat #(list [%2 (first %1)] (second %1)); we help the second match by matching on the number
                                     pbody (range))))))))
