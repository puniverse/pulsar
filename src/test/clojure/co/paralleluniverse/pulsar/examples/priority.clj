(ns co.paralleluniverse.pulsar.examples.priority
  "A selective receive example"
  (:use co.paralleluniverse.pulsar.core))

;; This example is intended to be a line-by-line translation of
;; [this example](http://learnyousomeerlang.com/more-on-multiprocessing#selective-receives) from the book *Learn You Some Erlang for great good!*,
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
