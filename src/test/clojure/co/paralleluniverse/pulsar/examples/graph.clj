; Copyright (C) 2013, Jan Stępień. All rights reserved.
; Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.

(ns co.paralleluniverse.pulsar.examples.graph
  (:use [co.paralleluniverse.pulsar core actors]))

(defn- actors-in-a-ring
  [n]
  (let [actor (fn [] (spawn
                      #(loop [npings (int n)
                              npongs (int n)]
                         (when (or (pos? npings) (pos? npongs))
                           (receive
                            [:start actors] (do
                                              (doseq [actor actors]
                                                (! actor [:ping @self])) ; can also try with !!
                                              (recur npings npongs))
                            [:ping from]    (do
                                              (! from :pong)
                                              (recur (dec npings) npongs))
                            :pong           (recur npings (dec npongs)))))))
        actors (take n (repeatedly actor))]
    actors))

(defn run
  [n]
  (let [all (actors-in-a-ring n)]
    (doseq [actor all]
      (! actor [:start all]))
    (doseq [actor all]
      (join actor))))

(defn -main
  ([] (-main "100"))
  ([times & _]
   (let [n (Integer/parseInt times)]
     (time
      (dotimes [_ n]
        (time (run n)))))
   (System/exit 0)))
