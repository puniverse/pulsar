(ns co.paralleluniverse.pulsar.examples.cluster.ping
  "A distributed version of the classic ping-pong example"
  (:use [co.paralleluniverse.pulsar core actors]))

;; for running see comment in pong.clj

(defsfn ping [n]
  (if (== n 0)
    (do
      (! :pong :finished)
      (println "ping finished"))
    (do
      (! :pong [:ping @self])
      (receive
        :pong (println "Ping received pong"))
      (recur (dec n)))))

(defn -main []
  (spawn ping 3)
  :ok)
