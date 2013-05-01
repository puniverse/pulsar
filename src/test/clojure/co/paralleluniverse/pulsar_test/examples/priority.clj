(ns co.paralleluniverse.pulsar-test.examples.priority
  "A selective receive example"
  (:use co.paralleluniverse.pulsar))

;; This example is intended to be a line-by-line translation of
;; [this example](http://stackoverflow.com/questions/10971923/selective-receiving-in-erlang) postend on stackoverflow.com,
;; so it is not written in idiomatic Clojure

(declare normal)

(defsusfn important []
  (receive
   [(priority :guard #(> % 10)) msg] (cons msg (important))
   :after 0 (normal)))

(defsusfn normal []
  (receive
   [_ msg] (cons msg (normal))
   :after 0 ()))

(defn -main []
  (join (spawn
         (fn []
           (! @self [15 :high])
           (! @self [7 :low])
           (! @self [1 :low])
           (! @self [17 :high])
           (important)))))
