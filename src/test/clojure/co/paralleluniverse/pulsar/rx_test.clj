; Pulsar: lightweight threads and Erlang-like actors for Clojure.
; Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
;
; This program and the accompanying materials are dual-licensed under
; either the terms of the Eclipse Public License v1.0 as published by
; the Eclipse Foundation
;
;   or (per the licensee's choosing)
;
; under the terms of the GNU Lesser General Public License version 3.0
; as published by the Free Software Foundation.

(ns co.paralleluniverse.pulsar.rx-test
  (:use midje.sweet
        co.paralleluniverse.pulsar.core)
  (:refer-clojure :exclude [promise await])
  (:require [co.paralleluniverse.pulsar.rx :as rx]
            [midje.checking.core :as checking]))

(facts "test map and filter"
       (fact "test filter"
             (let [ch (channel)
                   fiber (spawn-fiber
                           (fn []
                             (let [ch1 (rx/filter even? ch)
                                   m1 (rcv ch1)
                                   m2 (rcv ch1)
                                   m3 (rcv ch1)]
                               (list m1 m2 m3))))]
               (sleep 20)
               (snd ch 1)
               (snd ch 2)
               (sleep 20)
               (snd ch 3)
               (snd ch 4)
               (sleep 20)
               (snd ch 5)
               (close! ch)
               (join fiber))  => '(2 4 nil))
       (fact "test map"
             (let [ch (channel)
                   fiber (spawn-fiber
                           (fn []
                             (let [ch1 (rx/map #(+ 10 %) ch)
                                   m1 (rcv ch1)
                                   m2 (rcv ch1)
                                   m3 (rcv ch1)
                                   m4 (rcv ch1)]
                               (list m1 m2 m3 m4))))]
               (sleep 20)
               (snd ch 1)
               (snd ch 2)
               (sleep 20)
               (snd ch 3)
               (close! ch)
               (join fiber))  => '(11 12 13 nil))
       (fact "test filter then map"
             (let [ch (channel)
                   fiber (spawn-fiber
                           (fn []
                             (let [ch1 (rx/map #(+ 10 %) (rx/filter even? ch))
                                   m1 (rcv ch1)
                                   m2 (rcv ch1)
                                   m3 (rcv ch1)]
                               (list m1 m2 m3))))]
               (sleep 20)
               (snd ch 1)
               (snd ch 2)
               (sleep 20)
               (snd ch 3)
               (snd ch 4)
               (sleep 20)
               (snd ch 5)
               (close! ch)
               (join fiber))  => '(12 14 nil)))

(facts "test snd- map and filter"
       (fact "test filter"
             (let [ch (channel)
                   fiber (spawn-fiber
                           (fn []
                             (let [m1 (rcv ch)
                                   m2 (rcv ch)
                                   m3 (rcv ch)]
                               (list m1 m2 m3))))
                   ch1 (rx/snd-filter even? ch)]
               (sleep 20)
               (snd ch1 1)
               (snd ch1 2)
               (sleep 20)
               (snd ch1 3)
               (snd ch1 4)
               (sleep 20)
               (snd ch1 5)
               (close! ch1)
               (join fiber))  => '(2 4 nil))
       (fact "test map"
             (let [ch (channel)
                   fiber (spawn-fiber
                           (fn []
                             (let [m1 (rcv ch)
                                   m2 (rcv ch)
                                   m3 (rcv ch)
                                   m4 (rcv ch)]
                               (list m1 m2 m3 m4))))
                   ch1 (rx/snd-map #(+ 10 %) ch)]
               (sleep 20)
               (snd ch1 1)
               (snd ch1 2)
               (sleep 20)
               (snd ch1 3)
               (close! ch1)
               (join fiber))  => '(11 12 13 nil))
       (fact "test filter then map"
             (let [ch (channel)
                   fiber (spawn-fiber
                           (fn []
                             (let [m1 (rcv ch)
                                   m2 (rcv ch)
                                   m3 (rcv ch)]
                               (list m1 m2 m3))))
                   ch1 (rx/snd-map #(+ 10 %) (rx/snd-filter even? ch))]
               (sleep 20)
               (snd ch1 1)
               (snd ch1 2)
               (sleep 20)
               (snd ch1 3)
               (snd ch1 4)
               (sleep 20)
               (snd ch1 5)
               (close! ch1)
               (join fiber))  => '(12 14 nil)))

(facts "test group"
       (fact "Receive from channel group"
             (let [ch1 (channel)
                   ch2 (channel)
                   ch3 (channel)
                   grp (rx/group ch1 ch2 ch3)
                   fiber (spawn-fiber
                           (fn []
                             (let [m1 (rcv grp)
                                   m2 (rcv ch2)
                                   m3 (rcv grp)]
                               (list m1 m2 m3))))]

               (sleep 20)
               (snd ch1 "hello")
               (sleep 20)
               (snd ch2 "world!")
               (sleep 20)
               (snd ch3 "foo")
               (join fiber))  => '("hello" "world!" "foo"))
       (fact "Receive from channel group with timeout"
             (let [ch1 (channel)
                   ch2 (channel)
                   ch3 (channel)
                   grp (rx/group ch1 ch2 ch3)
                   fiber (spawn-fiber
                           (fn []
                             (let [m1 (rcv grp)
                                   m2 (rcv grp 10 :ms)
                                   m3 (rcv grp 100 :ms)]
                               (list m1 m2 m3))))]
               (sleep 20)
               (snd ch1 "hello")
               (sleep 100)
               (snd ch3 "world!")
               (join fiber))  => '("hello" nil "world!")))

(fact "test zip"
      (let [ch1 (channel 10)
            ch2 (channel 10)
            fiber (spawn-fiber
                    (fn []
                      (let [ch (rx/zip ch1 ch2)
                            m1 (rcv ch)
                            m2 (rcv ch)
                            m3 (rcv ch)]
                        (list m1 m2 m3))))]
        (sleep 20)
        (snd ch1 "a")
        (snd ch1 "b")
        (sleep 20)
        (snd ch2 1)
        (sleep 20)
        (snd ch2 2)
        (snd ch2 3)
        (sleep 20)
        (snd ch1 "c")
        (close! ch1)
        (close! ch2)
        (join fiber))  => '(["a" 1] ["b" 2] ["c" 3]))

(fact "test mapcat"
      (let [ch (channel 10)
            fiber (spawn-fiber
                    (fn []
                      (let
                          [ch1 (rx/mapcat (fn [x]
                                            (cond
                                              (= x 3) nil
                                              (even? x) [(* 10 x) (* 100 x) (* 1000 x)]
                                              :else x))
                                          ch)
                           m1 (rcv ch1)
                           m2 (rcv ch1)
                           m3 (rcv ch1)
                           m4 (rcv ch1)
                           m5 (rcv ch1)
                           m6 (rcv ch1)
                           m7 (rcv ch1)
                           m8 (rcv ch1)
                           m9 (rcv ch1)]
                        (list m1 m2 m3 m4 m5 m6 m7 m8 m9))))]
        (sleep 20)
        (snd ch 1)
        (snd ch 2)
        (sleep 20)
        (snd ch 3)
        (snd ch 4)
        (snd ch 5)
        (close! ch)
        (join fiber))  => '(1 20 200 2000 40 400 4000 5 nil))

(fact "test fiber-transform"
      (let [in (channel)
            out (channel)
            fiber (spawn-fiber
                    (fn []
                      (list (rcv out) (rcv out) (rcv out) (rcv out))))]
        (rx/fiber-transform in out (fn [in out]
                                     (if-let [x (rcv in)]
                                       (do
                                         (when (zero? (mod x 2))
                                           (snd out (* x 10)))
                                         (recur in out))
                                       (do (snd out 1234)
                                           (close! out)))))
        (sleep 20)
        (snd in 1)
        (snd in 2)
        (sleep 20)
        (snd in 3)
        (snd in 4)
        (sleep 20)
        (snd in 5)
        (close! in)
        (join fiber))  => '(20 40 1234 nil))