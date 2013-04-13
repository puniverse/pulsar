;;;
;;;
;;;
;;;

(ns co.paralleluniverse.pulsar
  (:import [jsr166e ForkJoinPool ForkJoinTask]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.strands SuspendableCallable]
           [co.paralleluniverse.fibers Fiber]
           [co.paralleluniverse.fibers.instrument ClojureRetransform]
           [co.paralleluniverse.strands.channels Channel]
           [co.paralleluniverse.strands.channels ObjectChannel IntChannel LongChannel FloatChannel DoubleChannel]
           [co.paralleluniverse.actors PulsarActor]))

(use '[clojure.core.match :only (match)])

;; Private util functions

(defn- extract-args 
  [pds xs]
  "Used to simplify optional parameters in functions. 
  Takes a sequence of [predicate? default] pairs, and a sequence of arguments. Tests the first predicate against
  the first argument. If the predicate succeeds, emits the argument's value; if not - the default, and tries the
  next pair with the argument. Any remaining arguments are copied to the output as-is."
  (if (seq pds)
    (let [[p? d] (first pds)
          x      (first xs)] 
      (println x)
      (if (p? x) 
        (cons x (extract-args (rest pds) (rest xs)))
        (cons d (extract-args (rest pds) xs))))
    (seq xs)))

(defn- sequentialize
  "Takes a function of a single argument and returns a function that either takes any number of arguments or a
  a single sequence, and applies the original function to each argument or each element of the sequence"
  [f]
  (fn 
    ([x] (if (coll? x) (map f x) (f x)))
    ([x & xs] (map f (cons x xs)))))

;; ## Global fork/join pool

(defn available-processors
  "Returns the number of available processors"
  []
  (.availableProcessors (Runtime/getRuntime)))

(def fj-pool
  "A global fork/join pool. The pool uses all available processors and runs in the async mode."
  (ForkJoinPool. (available-processors) jsr166e.ForkJoinPool/defaultForkJoinWorkerThreadFactory nil true))

(defn- in-fj-pool?
  []
  (ForkJoinTask/inForkJoinPool))

;; Make agents use the global fork-join pool
(set-agent-send-executor! fj-pool)
(set-agent-send-off-executor! fj-pool)

;; ## Suspendable functions
;; Only functions that have been especially instrumented can perform blocking actions
;; while running in a fiber.

(def suspendable!
  "Makes a function suspendable"
  (sequentialize 
   #((ClojureRetransform/retransform %) 
     %)))

(defn suspendable?
  [f]
  (.isAnnotationPresent (.getClass ^Object f) co.paralleluniverse.fibers.Instrumented))

(defn- ^SuspendableCallable wrap
  "wrap a clojure function as a SuspendableCallable"
  [f]
  (suspendable! (ClojureRetransform/wrap f)))


(defmacro susfn
    "Creates a suspendable function that can be used by a fiber or actor"
    [& sigs]
    `(suspendable (fn ~@sigs)))

;; ## Fibers

(defn ^Fiber fiber
  "Creates a new fiber (a lightweight thread) running in a fork/join pool."
  [& args]
  (if (or (not (in-fj-pool?))
          (seq (filter #(instance? ForkJoinPool %) args)))
    (let [[^String name ^ForkJoinPool pool ^Integer stacksize f] (extract-args [[string? nil] [#(instance? ForkJoinPool %) fj-pool] [integer? -1]] args)]
      (Fiber. name pool (int stacksize) (wrap f)))
    (let [[^String name ^Integer stacksize f] (extract-args [[string? nil] [integer? -1]] args)]
      (Fiber. name (int stacksize) (wrap f)))))

(defn start
  "Starts a fiber"
  [^Fiber fiber]
  (.start fiber))

(defn spawn-fiber
  "Creates and starts a fiber"
  [& args]
  (start (apply fiber args)))

(defn current-fiber
  "Returns the currently running lightweight-thread or nil if none"
  []
  (Fiber/currentFiber))


;; ## Strands
;; A strand is either a thread or a fiber.

(defn ^Strand current-strand
  "Returns the currently running fiber or current thread in case of new active fiber"
  []
  (Strand/currentStrand))

;; ## Channels

(defn attach!
  "Sets a channel's owning strand (fiber or thread)"
  [^Channel channel strand]
  (.setStrand channel strand))

(defn channel
  "Creates a channel"
  ([size] (ObjectChannel/create size))
  ([] (ObjectChannel/create -1)))

(defn send1
  [^Channel channel message]
  (.send channel message))

(defn receive
  [^Channel channel]
  (.receive channel))

;; ### Primitive channels

(defn ^IntChannel int-channel
  "Creates an int channel"
  ([size] (IntChannel/create size))
  ([] (IntChannel/create -1)))

(defn send-int
  [^IntChannel channel message]
  (.send channel (int message)))

(defn ^int receive-int
  [^IntChannel channel]
  (.receiveInt channel))

(defn ^LongChannel long-channel
  "Creates a long channel"
  ([size] (LongChannel/create size))
  ([] (LongChannel/create -1)))

(defn send-long
  [^LongChannel channel message]
  (.send channel (long message)))

(defn ^long receive-long
  [^LongChannel channel]
  (.receiveLong channel))

(defn ^FloatChannel float-channel
  "Creates a float channel"
  ([size] (FloatChannel/create size))
  ([] (FloatChannel/create -1)))

(defn send-float
  [^FloatChannel channel message]
  (.send channel (float message)))

(defn ^float receive-float
  [^FloatChannel channel]
  (.receiveFloat channel))

(defn ^DoubleChannel double-channel
  "Creates a double channel"
  ([size] (DoubleChannel/create size))
  ([] (DoubleChannel/create -1)))

(defn send-double
  [^FloatChannel channel message]
  (.send channel (double message)))

(defn ^double receive-double
  [^DoubleChannel channel]
  (.receiveDouble channel))


;; ## Actors






