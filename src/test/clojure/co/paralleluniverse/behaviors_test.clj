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

(ns co.paralleluniverse.behaviors-test
  (:use midje.sweet
        co.paralleluniverse.pulsar
        co.paralleluniverse.pulsar.behaviors)
  (:import [java.util.concurrent TimeUnit TimeoutException ExecutionException]
           [co.paralleluniverse.common.util Debug]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.fibers Fiber]))

;; ## gen-server

(fact "When gen-server starts then init is called"
      (let [called (atom false)
            gs (spawn
                (gen-server (reify Server
                              (init [_]
                                    (reset! called true)
                                    (stop))
                              (terminate [_ cause]))))]
        (join gs)
        @called) => true)

(fact "When no messages then handle-timeout is called"
      (let [times (atom 0)
            gs (spawn
                (gen-server :timeout 20
                            (reify Server
                              (init [_])
                              (handle-timeout [_]
                                              (if (< @times 5)
                                                (swap! times inc)
                                                (stop)))
                              (terminate [_ cause]))))]
        (fact
         (join gs 50 :ms) => (throws TimeoutException))
        (join gs 200 :ms)
        @times) => 5)

(fact "When no messages then handle-timeout is called"
      (let [times (atom 0)
            gs (spawn
                (gen-server (reify Server
                              (init [_])
                              (terminate [_ cause]
                                         (fact cause => nil)))))]
        (fact
         (join gs 50 :ms) => (throws TimeoutException))
        (shutdown gs)
        (join gs)) => nil)


(facts "gen-server call"
       (fact "When gen-server call then result is returned"
             (let [gs (spawn
                       (gen-server (reify Server
                                     (init [_])
                                     (terminate [_ cause])
                                     (handle-call [_ from id [a b]]
                                                  (+ a b)))))]
               (call gs 3 4) => 7))
       (fact "When gen-server call from fiber then result is returned"
             (let [gs (spawn
                       (gen-server (reify Server
                                     (init [_])
                                     (terminate [_ cause])
                                     (handle-call [_ from id [a b]]
                                                  (+ a b)))))
                   fib (spawn-fiber #(call gs 3 4))]
               (join fib) => 7))
       (fact "When gen-server call from actor then result is returned"
             (let [gs (spawn (gen-server
                              (reify Server
                                (init [_])
                                (terminate [_ cause])
                                (handle-call [_ from id [a b]]
                                             (+ a b)))))
                   actor (spawn #(call gs 3 4))]
               (join actor) => 7))
       (fact "When handle-call throws exception then call throws it"
             (let [gs (spawn
                       (gen-server (reify Server
                                     (init [_])
                                     (terminate [_ cause])
                                     (handle-call [_ from id [a b]]
                                                  (throw (Exception. "oops!"))))))]
               (call gs 3 4) => (throws Exception "oops!"))))

(fact "when gen-server doesn't respond then timeout"
      (let [gs (spawn
                (gen-server (reify Server
                              (init [_])
                              (terminate [_ cause])
                              (handle-call [_ from id [a b]]
                                           (Strand/sleep 100)
                                           (+ a b)))))]
        (call-timed gs 10 :ms 3 4) => (throws TimeoutException)))

(fact "when reply is called return value in call"
      (let [info (atom {})
            gs (spawn
                (gen-server :timeout 50
                            (reify Server
                              (init [_])
                              (terminate [_ cause])
                              (handle-call [_ from id [a b]]
                                           (swap! info assoc :a a :b b :from from :id id)
                                           nil)
                              (handle-timeout [_]
                                              (let [{:keys [a b from id]} @info]
                                                (when id
                                                  (reply from id (+ a b))))))))]
        (fact
         (call-timed gs 10 :ms 3 4) => (throws TimeoutException))
        (call-timed gs 100 :ms 5 6)) => 11)


(fact "When cast then handle-cast is called"
      (let [res (atom nil)
            gs (spawn
                (gen-server (reify Server
                              (init [_])
                              (terminate [_ cause])
                              (handle-cast [_ from id [a b]]
                                           (reset! res (+ a b))))))]
        (cast gs 3 4)
        (shutdown gs)
        (join gs)
        @res => 7))

(fact "Messages sent to a gen-server are passed to handle-info"
      (let [res (atom nil)
            gs (spawn
                (gen-server (reify Server
                              (init [_])
                              (terminate [_ cause])
                              (handle-info [_ m]
                                           (reset! res m)))))]
        (! gs :hi)
        (shutdown gs)
        (join gs)
        @res => :hi))

(fact "When handle-info throws exception, terminate is called with the cause"
      (let [gs (spawn
                (gen-server (reify Server
                              (init [_])
                              (terminate [_ cause]
                                         (fact cause => (every-checker #(instance? Exception %) #(= (.getMessage %) "oops!"))))
                              (handle-info [_ m]
                                           (throw (Exception. "oops!"))))))]
        (! gs :hi)
        (join gs)) => (throws Exception "oops!"))

;; ## supervisor