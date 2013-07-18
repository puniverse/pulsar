(ns co.paralleluniverse.pulsar.examples.distributed.pong
  "The classic ping-pong example from the Erlang tutorial"
  (:use [co.paralleluniverse.pulsar core actors]))

;; This example is intended to be a line-by-line translation of the canonical
;; Erlang [ping-pong example](http://www.erlang.org/doc/getting_started/conc_prog.html#id67347),
;; so it is not written in idiomatic Clojure.

(defsfn pong []
  (receive
    :finished (println "Pong finished")
    [:ping ping] (do
                   (println "Pong received ping")
                   (! ping :pong)
                   (recur))))

(defn -main []
    (let [a1 (register! "pong" (spawn pong))]
      (join a1)
      (System/exit 0)))
