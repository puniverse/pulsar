;;;
;;;
;;;
;;;

(ns co.paralleluniverse.pulsar
  (:import [jsr166e ForkJoinPool]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.strands SuspendableCallable]
           [co.paralleluniverse.fibers Fiber]
           [co.paralleluniverse.fibers.instrument ClojureRetransform]
           [co.paralleluniverse.strands.channels Channel]
           [co.paralleluniverse.strands.channels ObjectChannel IntChannel LongChannel FloatChannel DoubleChannel]
           [co.paralleluniverse.actors PulsarActor]))

(use '[clojure.core.match :only (match)])


;; ## fibers

(defn available-processors
  "Returns the number of available processors"
  []
  (.availableProcessors (Runtime/getRuntime)))

;; A global forkjoin pool
(def fj-pool
  (ForkJoinPool. (available-processors) jsr166e.ForkJoinPool/defaultForkJoinWorkerThreadFactory nil true))

(defn- ^SuspendableCallable wrap
  "wrap a clojure function as a SuspendableCallable"
  [f]
  (ClojureRetransform/wrap f))


(defn- extract-args [pds xs]
  (if (seq pds)
    (let [[p? d] (first pds)
          x      (first xs)] 
      (println x)
      (if (p? x) 
        (cons x (extract-args (rest pds) (rest xs)))
        (cons d (extract-args (rest pds) xs))))
    (seq xs)))

(defn fiber 
  [& args]
  (if (seq (filter #(instance? ForkJoinPool %) args))
    (let [[name pool stacksize f] (extract-args [[string? nil] [#(instance? ForkJoinPool %) nil] [integer? -1]] args)]
      (Fiber. name pool (int stacksize) (wrap f)))
    (let [[name stacksize f] (extract-args [[string? nil] [integer? -1]] args)]
      (Fiber. name (int stacksize) (wrap f)))))

(defn suspendable
  "Makes a function suspendable"
  [f]
  (ClojureRetransform/retransform f)
  f)

(defmacro susfn
  "Creates a suspendable function that can be used by a fiber or actor"
  [& sigs]
  (suspendable (fn ~@sigs)))

(defn current-fiber
  "Returns the currently running lightweight-thread or nil if none"
  []
  (Fiber/currentFiber))


;; ## Strands

(defn current-strand
  "Returns the currently running fiber or current thread in case of new active fiber"
  []
  (Strand/currentStrand))

;; ## Channels

(attach!
 "Sets a channel's owning strand (fiber or thread)"
 [^Channel channel strand]
 (.setStrand channel strand))

(defn channel
  "Creates a channel"
  ([size] (ObjectChannel/create size))
  ([] (ObjectChannel/create -1)))

(defn send
  [channel message]
  (.send channel message))

(defn receive
  [channel]
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






