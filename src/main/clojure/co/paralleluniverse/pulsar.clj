(ns co.paralleluniverse.pulsar
  (:import [java.lang.annotation Retention RetentionPolicy Target ElementType]
           [co.paralleluniverse.fibers Fiber]
           [co.paralleluniverse.fibers.instrument ClojureRetransform]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.strands.channels Channel]
           [co.paralleluniverse.strands.channels ObjectChannel IntChannel LongChannel FloatChannel DoubleChannel]
           [co.paralleluniverse.actors PulsarActor]))

(use '[clojure.core.match :only (match)])


;; ## lightweight threads

(defn available-processors
  "Returns the number of available processors"
  []
  (.availableProcessors (Runtime/getRuntime)))

;; A global forkjoin pool
(def fj-pool
  (jsr166e.ForkJoinPool. (available-processors) jsr166e.ForkJoinPool/defaultForkJoinWorkerThreadFactory nil true))

(defn current-strand
  "Returns the currently running fiber or current thread in case of new active fiber"
  []
  (Strand/currentStrand))

(defn current-fiber
  "Returns the currently running lightweight-thread or nil if none"
  []
  (Fiber/currentFiber))


;; ## Actors






