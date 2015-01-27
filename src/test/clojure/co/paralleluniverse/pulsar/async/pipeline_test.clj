; Pulsar: lightweight threads and Erlang-like actors for Clojure.
; Copyright (C) 2013-2015, Parallel Universe Software Co. All rights reserved.
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
; Tests are derived from core.async (https://github.com/clojure/core.async).
; Copyright (C) 2013 Rich Hickey and contributors.
; Distributed under the Eclipse Public License, the same as Clojure.
;
(ns co.paralleluniverse.pulsar.async.pipeline-test
  (:use midje.sweet)
  (:require [co.paralleluniverse.pulsar.core :as p]
            [co.paralleluniverse.pulsar.async :as a :refer [<! >! <!! >!! go go-loop thread fiber chan close! to-chan
                                                            pipeline pipeline-blocking pipeline-async]])
  (:import (co.paralleluniverse.strands Strand)))


;; in Clojure 1.7, use (map f) instead of this
(defn mapping [f]
  (p/sfn [f1]
    (p/sfn
      ([] (f1))
      ([result] (f1 result))
      ([result input]
         (f1 result (f input)))
      ([result input & inputs]
         (f1 result (apply f input inputs))))))

(defn pipeline-tester [pipeline-fn n inputs xf]
  (let [cin (to-chan inputs)
        cout (chan 1)]
    (pipeline-fn n cout xf cin)
    (<!! (go-loop [acc []] 
                  (let [val (<! cout)]
                    (if (not (nil? val))
                          (recur (conj acc val))
                          acc))))))

(def identity-mapping (mapping identity))
(defn identity-async [v ch] (thread (>!! ch v) (close! ch)))
(p/defsfn identity-async-fiber [v ch] (fiber (>!! ch v) (close! ch)))

(tabular "Test sizes"
  (fact
    (let [r (range ?size)]
      (and
       (= r (pipeline-tester pipeline ?n r identity-mapping))
       (= r (pipeline-tester pipeline-blocking ?n r identity-mapping))
       (= r (pipeline-tester pipeline-async ?n r identity-async))
       (= r (pipeline-tester pipeline-async ?n r identity-async-fiber))
       )) => true)
    ?n ?size
    1 0
    1 10
    10 10
    20 10
    5 1000)

(fact "Test close?"
  (doseq [pf [pipeline pipeline-blocking]]
    (let [cout (chan 1)]
      (pf 5 cout identity-mapping (to-chan [1]) true)
      (fact (<!! cout) => 1)
      (fact (<!! cout) => nil))
    (let [cout (chan 1)]
      (pf 5 cout identity-mapping (to-chan [1]) false)
      (fact (<!! cout) => 1)
      (>!! cout :more)
      (fact (<!! cout) => :more))
    (let [cout (chan 1)]
      (pf 5 cout identity-mapping (to-chan [1]) nil)
      (fact (<!! cout) => 1)
      (>!! cout :more)
      (fact (<!! cout) => :more))))

(fact "Test ex-handler"
  (doseq [pf [pipeline pipeline-blocking]]
    (let [cout (chan 1)
          chex (chan 1)
          ex-mapping (mapping (p/sfn [x] (if (= x 3) (throw (ex-info "err" {:data x})) x)))
          ex-handler (p/sfn [e] (do (>!! chex e) :err))]
      (pf 5 cout ex-mapping (to-chan [1 2 3 4]) true ex-handler)
      (fact (<!! cout) => 1)
      (fact (<!! cout) => 2)
      (fact (<!! cout) => :err)
      (fact (<!! cout) => 4)
      (fact (ex-data (<!! chex)) => {:data 3}))))

(p/defsfn multiplier-async [v ch]
  (fiber
    (dotimes [i v]
      (>!! ch i))
    (close! ch)))

(fact "Test af-multiplier"
  (pipeline-tester pipeline-async 2 (range 1 5) multiplier-async)
  => [0 0 1 0 1 2 0 1 2 3])

(def sleep-mapping (mapping (p/sfn [ms] (do (Strand/sleep ms) ms))))

(let [times [2000 50 1000 100]]
  (fact "Test blocking"
    (pipeline-tester pipeline-blocking 2 times sleep-mapping)
    => times))

(p/defsfn slow-fib [n]
  (if (< n 2) n (+ (slow-fib (- n 1)) (slow-fib (- n 2)))))

(let [input (take 50 (cycle (range 15 38)))]
  (fact "Test compute"
    (last (pipeline-tester pipeline 8 input (mapping slow-fib)))
    => (slow-fib (last input))))

(fact "Test async"
  (pipeline-tester pipeline-async 1 (range 100)
                   (p/sfn [v ch] (future (>!! ch (inc v)) (close! ch))))
  => (range 1 101))
