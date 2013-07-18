(ns co.paralleluniverse.pulsar.examples.distributed.ping
  "The classic ping-pong example from the Erlang tutorial"
  (:use [co.paralleluniverse.pulsar core actors]))

;; This example is intended to be a line-by-line translation of the canonical
;; Erlang [ping-pong example](http://www.erlang.org/doc/getting_started/conc_prog.html#id67347),
;; so it is not written in idiomatic Clojure.
;; for running see comment in pong.clj

(defsfn ping [n]
  (if (== n 0)
    (do
      (! "pong" :finished)
      (println "ping finished"))
    (do
      (! "pong" [:ping @self])
      (receive
        :pong (println "Ping received pong"))
      (recur (dec n)))))

(defn -main []
  (spawn ping 3)
  :ok)
