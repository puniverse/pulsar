(ns co.paralleluniverse.pulsar.examples.tkb
  (:use co.paralleluniverse.pulsar.core)
  (:import [co.paralleluniverse.strands.channels ReceivePort$EOFException])
  (:refer-clojure :exclude [promise await])
  (:require [co.paralleluniverse.pulsar.rx :as rx]))

;(alter-var-root #'*compiler-options* (fn [_] {:disable-locals-clearing true}))
(println "======" clojure.core/*compiler-options*)
(defn foo []
  (let [a "hello"]
    (^:once fn* [] (println "!!!" a))))

(defn bar []
     (let [f (foo)]
       (f)
       (f)))

(bar)

(defn -main[])


