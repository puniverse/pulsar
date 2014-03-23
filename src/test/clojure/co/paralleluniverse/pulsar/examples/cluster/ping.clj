(ns co.paralleluniverse.pulsar.examples.cluster.ping
  "A distributed version of the classic ping-pong example"
  (:use [co.paralleluniverse.pulsar core actors])
  (:refer-clojure :exclude [promise await]))

;; for running see comment in pong.clj

(defsfn ping [n]
  (dotimes [i n]
    (! :pong [:ping @self])
    (receive
      :pong (println "Ping received pong")))
  (! :pong :finished)
  (println "ping finished"))

(defn -main []
  (println "Ping started")
  (when (nil? (whereis :pong))
    (println "Waiting for pong to register...")
    (loop []
      (when (nil? (whereis :pong))
        (Thread/sleep 500)
        (recur))))
  (join (spawn ping 3))
  :ok)
