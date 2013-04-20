(ns co.paralleluniverse.pulsar.examples.pingpong
  "The classic ping-pong example from the Erlang tutorial"
  (:use co.paralleluniverse.pulsar))

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
