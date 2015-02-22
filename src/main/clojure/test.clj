(ns test
"Verify that exceptions thrown on a thread pool managed by
core.async will propagate out to the JVM's default uncaught
exception handler."
(:require
  [clojure.stacktrace :refer [root-cause]]
  [co.paralleluniverse.pulsar.core :as p]
  [co.paralleluniverse.pulsar.async :refer [chan go thread put! take! <!! >!!]])
(:import (co.paralleluniverse.fibers Fiber)
  (co.paralleluniverse.strands Strand$UncaughtExceptionHandler)))

(defn with-default-uncaught-exception-handler-thread [handler f]
  (let [old-handler-thread (Thread/getDefaultUncaughtExceptionHandler)]
      (Thread/setDefaultUncaughtExceptionHandler
        (reify Thread$UncaughtExceptionHandler
          (uncaughtException [_ thread throwable]
            (println "Called generic handler") (handler thread throwable))))
      (f)
      (Thread/setDefaultUncaughtExceptionHandler old-handler-thread)))

(defn -main []
      (let [log (p/promise)
            ex (Exception. "This exception is expected")]
        (with-default-uncaught-exception-handler-thread
          (fn [_ throwable] (println "Called handler") (deliver log throwable))
          #(let [ret (thread (println "Throwing") (throw ex))]
            (<!! ret)))
        (println (identical? ex (root-cause @log)))))
