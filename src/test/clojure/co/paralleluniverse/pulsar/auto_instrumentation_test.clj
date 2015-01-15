(ns co.paralleluniverse.pulsar.auto-instrumentation-test
(:use midje.sweet
  co.paralleluniverse.pulsar.core)
  (:refer-clojure :exclude [promise await bean])
  (:import [co.paralleluniverse.fibers Fiber]
           (java.io InputStream)))

(defprotocol P1
  (foo ^Integer [this]))
(defprotocol P2
  (bar-me ^Integer [this ^Integer y]))

(deftype T [^Integer a ^Integer b ^Integer c]
  P1
  (foo [_] (println "T.foo before sleep") (Fiber/sleep 100) (println "T.foo after sleep") a))
(extend-type T
  P2
  (bar-me [this y] (println "T.bar-me before sleep") (Fiber/sleep 100) (println "T.bar-me after sleep") (+ (.c this) y)))

(defrecord R [^Integer a ^Integer b ^Integer c]
  P1
  (foo [_] (println "R.foo before sleep") (Fiber/sleep 100) (println "R.foo after sleep") b))
(extend-type R
  P2
  (bar-me [this y] (println "R.bar-me before sleep") (Fiber/sleep 100) (println "R.bar-me after sleep") (* (:c this) y)))

(def a
  (reify
    P1
    (foo [_] (println "a.foo before sleep") (Fiber/sleep 100) (println "a.foo after sleep") 17)
    P2
    (bar-me [_ y] (println "a.bar-me before sleep") (Fiber/sleep 100) (println "a.bar-me after sleep") y)))

(def px (proxy [InputStream] [] (read [] (println "px.read before sleep") (Fiber/sleep 100) (println "px.read after sleep") -1)))

(def t (->T 1 2 3))

(def r (->R 3 2 1))

(defmulti area :Shape)
(defn rect [wd ht] {:Shape :Rect :wd wd :ht ht})
(defn circle [radius] {:Shape :Circle :radius radius})

(defmethod area :Rect [r]
  (println "area :Rect before sleep") (Fiber/sleep 100) (println "area :Rect after sleep") (* (:wd r) (:ht r)))
(defmethod area :Circle [c]
  (println "area :Circle before sleep") (Fiber/sleep 100) (println "area :Circle after sleep") (* (. Math PI) (* (:radius c) (:radius c))))
(defmethod area :default [x] :oops)

(def rect (rect 4 13))
(def circle (circle 12))

(defn simple-fun [] (println "simple-fun before sleep") (Fiber/sleep 100) (println "simple-fun after sleep") 17)

(fact "Clojure language features work with auto-instrumentation" :auto-instrumentation
  (join (spawn-fiber (fn []
                       [ ; (foo t)
                         ; (bar-me t 5)
                         ; (foo r)
                         ; (bar-me r 56)
                         ; (foo a)
                         ; (bar-me a 45)
                         (.read px)
                         (area rect)
                         (area circle)
                         (simple-fun)
                       ])))
  => [#_1 #_8 #_2 #_56 #_17 #_45 -1 52 452.3893421169302 17])
