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

(ns co.paralleluniverse.pulsar.lazyseq-test
  (:use midje.sweet
        co.paralleluniverse.pulsar.core)
  (:require [co.paralleluniverse.pulsar.lazyseq :as s :refer [channel->lazy-seq]]
            [midje.checking.core :as checking])
  (:refer-clojure :exclude [promise await])
  (:import [java.util.concurrent TimeUnit TimeoutException ExecutionException]
           [co.paralleluniverse.common.util Debug]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.fibers Fiber]))


(fact "Receive sequence with sleep"
      (let [ch (channel -1)
            fiber (spawn-fiber
                    #(let [s (s/take 5 (channel->lazy-seq ch))]
                      (s/doall s)))]
        (dotimes [m 10]
          (sleep 20)
          (snd ch m))
        (join fiber))  => '(0 1 2 3 4))

(fact "Map received sequence with sleep"
      (let [ch (channel -1)
            fiber (spawn-fiber (fn [] (s/doall (s/map #(* % %) (s/take 5 (channel->lazy-seq ch))))))]
        (dotimes [m 10]
          (sleep 20)
          (snd ch m))
        (join fiber)) => '(0 1 4 9 16))

(fact "Filter received sequence with sleep (odd)"
      (let [ch (channel -1)
            fiber (spawn-fiber
                    #(s/doall (s/filter odd? (s/take 5 (channel->lazy-seq ch)))))]
        (dotimes [m 10]
          (sleep 20)
          (snd ch m))
        (join fiber)) => '(1 3))

(fact "Filter received sequence with sleep (even)"
      (let [ch (channel -1)
            fiber (spawn-fiber
                    #(s/doall (s/filter even? (s/take 5 (channel->lazy-seq ch)))))]
        (dotimes [m 10]
          (sleep 20)
          (snd ch m))
        (join fiber)) => '(0 2 4))

(fact "Filter and map received sequence with sleep (even)"
      (let [ch (channel -1)
            fiber (spawn-fiber
                    (fn [] (s/doall
                             (s/filter #(> % 10)
                                       (s/map #(* % %)
                                              (s/filter even?
                                                        (s/take 5 (channel->lazy-seq ch))))))))]
        (dotimes [m 10]
          (sleep 20)
          (snd ch m))
        (join fiber)) => '(16))

(fact "Send and receive sequence"
      (let [ch (channel -1)
            fiber (spawn-fiber #(s/doall (s/take 5 (channel->lazy-seq ch))))]
        (snd-seq ch (take 10 (range)))
        (join fiber)) => '(0 1 2 3 4))

(fact "Map received sequence"
      (let [ch (channel -1)
            fiber (spawn-fiber (fn [] (s/doall (s/map #(* % %) (s/take 5 (channel->lazy-seq ch))))))]
        (snd-seq ch (take 10 (range)))
        (join fiber)) => '(0 1 4 9 16))

(fact "Filter received sequence"
      (let [ch (channel -1)
            fiber (spawn-fiber #(s/doall (s/filter even? (s/take 5 (channel->lazy-seq ch)))))]
        (snd-seq ch (take 10 (range)))
        (join fiber)) => '(0 2 4))

(fact "Filter huge sequence 1"
  (s/filter #(= % 1234567) (s/map inc (range 10000000))) => '(1234567))

#_(fact "Filter huge sequence2"
      (let [ch (channel 0)
            p (spawn-fiber (fn [] (snd-seq ch (map inc (range 10000000))) (close! ch)))
            c (spawn-fiber (fn [] (s/doall (s/filter #(= % 1234567) (channel->lazy-seq ch)))))]
        (join c)) => '(12345678))