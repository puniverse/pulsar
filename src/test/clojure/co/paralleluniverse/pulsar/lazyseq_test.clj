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


;(fact "Receive sequence with sleep"
;      (let [ch (channel -1)
;            fiber (spawn-fiber
;                    #(let [s (take 5 (channel->lazy-seq ch))]
;                      (doall s)))]
;        (dotimes [m 10]
;          (sleep 200)
;          (snd ch m))
;        (join fiber))  => '(0 1 2 3 4))
;
;(fact "Map received sequence with sleep"
;      (let [ch (channel -1)
;            fiber (spawn-fiber (fn [] (doall (map #(* % %) (take 5 (channel->lazy-seq ch))))))]
;        (dotimes [m 10]
;          (sleep 200)
;          (snd ch m))
;        (join fiber)) => '(0 1 4 9 16))
;
;(fact "Filter received sequence with sleep (odd)"
;      (let [ch (channel -1)
;            fiber (spawn-fiber
;                    #(doall (filter odd? (take 5 (channel->lazy-seq ch)))))]
;        (dotimes [m 10]
;          (sleep 200)
;          (snd ch m))
;        (let [res (join fiber)]
;          (co.paralleluniverse.common.util.Debug/dumpRecorder)
;          res) => '(1 3)))
;
;#_(fact "Filter received sequence with sleep (even)"
;      (let [ch (channel -1)
;            fiber (spawn-fiber
;                    #(doall (filter even? (take 5 (channel->lazy-seq ch)))))]
;        (dotimes [m 10]
;          (sleep 200)
;          (snd ch m))
;        (join fiber)) => '(0 2 4))
;
;#_(fact "Filter and map received sequence with sleep (even)"
;      (let [ch (channel -1)
;            fiber (spawn-fiber
;                    (fn [] (doall
;                             (filter #(> % 10)
;                                       (map #(* % %)
;                                              (filter even?
;                                                        (take 5 (channel->lazy-seq ch))))))))]
;        (dotimes [m 10]
;          (sleep 200)
;          (snd ch m))
;        (join fiber)) => '(16))
;
;(fact "Send and receive sequence"
;      (let [ch (channel -1)
;            fiber (spawn-fiber #(doall (take 5 (channel->lazy-seq ch))))]
;        (snd-seq ch (take 10 (range)))
;        (join fiber)) => '(0 1 2 3 4))
;
;(fact "Map received sequence"
;      (let [ch (channel -1)
;            fiber (spawn-fiber (fn [] (doall (map #(* % %) (take 5 (channel->lazy-seq ch))))))]
;        (snd-seq ch (take 10 (range)))
;        (join fiber)) => '(0 1 4 9 16))
;
;(fact "Filter received sequence"
;      (let [ch (channel -1)
;            fiber (spawn-fiber #(doall (filter even? (take 5 (channel->lazy-seq ch)))))]
;        (snd-seq ch (take 10 (range)))
;        (join fiber)) => '(0 2 4))
;
;(fact "Filter huge sequence 1"
;  (filter #(= % 1234567) (map inc (range 10000000))) => '(1234567))
;
;(fact "Filter huge sequence2"
;      (let [ch (channel 0)
;            p (spawn-fiber (fn [] (snd-seq ch (map inc (range 10000000))) (close! ch)))
;            c (spawn-fiber (fn [] (doall (filter #(= % 1234567) (channel->lazy-seq ch)))))]
;        (join c)) => '(12345678))