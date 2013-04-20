(ns co.paralleluniverse.pulsar.examples.pingpong
  "The classic ping-pong example from the Erlang tutorial"
  (:use co.paralleluniverse.pulsar))

;; This example is intended to be a line-by-line translation of the canonical 
;; Erlang [ping-pong example](http://www.erlang.org/doc/getting_started/conc_prog.html#id66868),
;; so it is not written in idiomatic Clojure.

(defsusfn ping [n]
  (if (== n 0)
    (do
      (! :pong :finished)
      (println "ping finished"))
    (do 
      (! :pong [:ping @self])
      (receive 
       :pong (println "Ping received pong"))
      (recur (dec n)))))

(defsusfn pong []
  (receive
   :finished (println "Pong finished")
   [:ping ping] (do
                  (println "Pong received ping")
                  (! ping :pong)
                  (recur))))

(defn -main []
  (let [a1 (register :pong (spawn (pong)))
        b1 (spawn (ping 3))]
    (join a1)
    (join b1)))
