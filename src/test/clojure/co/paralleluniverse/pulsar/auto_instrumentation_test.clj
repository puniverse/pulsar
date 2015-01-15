(ns co.paralleluniverse.pulsar.auto-instrumentation-test
  (:use midje.sweet
        co.paralleluniverse.pulsar.core)
  (:refer-clojure :exclude [promise await bean])
  (:import [co.paralleluniverse.fibers Fiber]
           (java.io InputStream)))

(defn stcktrc [] #_(.printStackTrace (Exception.) (System/err)))
(defn dbug [s] #_(.println System/err s))

(defprotocol P1
  (foo ^Integer [this]))
(defprotocol P2
  (bar-me ^Integer [this ^Integer y]))

(deftype T [^Integer a ^Integer b ^Integer c]
  P1
  (foo [_] (dbug "T.foo before sleep") (stcktrc) (Fiber/sleep 100) (dbug "T.foo after sleep") a))
(extend-type T
  P2
  (bar-me [this y] (dbug "T.bar-me before sleep") (stcktrc) (Fiber/sleep 100) (dbug "T.bar-me after sleep") (+ (.c this) y)))

(defrecord R [^Integer a ^Integer b ^Integer c]
  P1
  (foo [_] (dbug "R.foo before sleep") (stcktrc) (Fiber/sleep 100) (dbug "R.foo after sleep") b))
(extend-type R
  P2
  (bar-me [this y] (dbug "R.bar-me before sleep") (stcktrc) (Fiber/sleep 100) (dbug "R.bar-me after sleep") (* (:c this) y)))

(def a
  (reify
    P1
    (foo [_] (dbug "a.foo before sleep") (stcktrc) (Fiber/sleep 100) (dbug "a.foo after sleep") 17)
    P2
    (bar-me [_ y] (dbug "a.bar-me before sleep") (stcktrc) (Fiber/sleep 100) (dbug "a.bar-me after sleep") y)))

(def px (proxy [InputStream] [] (read [] (dbug "px.read before sleep") (stcktrc) (Fiber/sleep 100) (dbug "px.read after sleep") -1)))

(def t (->T 1 2 3))

(def r (->R 3 2 1))

(defmulti area :Shape)
(defn rect [wd ht] {:Shape :Rect :wd wd :ht ht})
(defn circle [radius] {:Shape :Circle :radius radius})

(defmethod area :Rect [r]
  (dbug "area :Rect before sleep") (stcktrc) (Fiber/sleep 100) (dbug "area :Rect after sleep") (* (:wd r) (:ht r)))
(defmethod area :Circle [c]
  (dbug "area :Circle before sleep") (stcktrc) (Fiber/sleep 100) (dbug "area :Circle after sleep") (* (. Math PI) (* (:radius c) (:radius c))))
(defmethod area :default [x] :oops)

(def rect (rect 4 13))
(def circle (circle 12))

(defn simple-fun [] (dbug "simple-fun before sleep") (stcktrc) (Fiber/sleep 100) (dbug "simple-fun after sleep") 17)

(def res (join (spawn-fiber (fn []
                               [ (foo t)
                                 (bar-me t 5)
                                 (foo r)
                                 (bar-me r 56)
                                 (foo a)
                                 (bar-me a 45)
                                 (.read px)
                                 (area rect)
                                 (area circle)
                                 (simple-fun)
                                ]))))

(fact "Clojure language features work with auto-instrumentation" :auto-instrumentation
  res => [1 8 2 56 17 45 -1 52 452.3893421169302 17])
