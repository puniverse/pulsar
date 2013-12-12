; Pulsar: lightweight threads and Erlang-like actors for Clojure.
; Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
;
; This program and the accompanying materials are dual-licensed under
; either the terms of the Eclipse Public License v1.0 as published by
; the Eclipse Foundation
;
;   or (per the licensee's choosing)
;
; under the terms of the GNU Lesser General Public License version 3.0
; as published by the Free Software Foundation.

(ns co.paralleluniverse.pulsar.examples.io
  "Uses Pulsar's fiber-blocking IO"
  (:use [co.paralleluniverse.pulsar core])
  (:refer-clojure :exclude [promise await])
  (:import [co.paralleluniverse.fibers.io FiberSocketChannel FiberServerSocketChannel]
           [java.nio ByteBuffer CharBuffer]
           [java.nio.charset Charset]
           [java.net InetSocketAddress]))

(def port 1234)

(defn buffer->string
  ([byte-buffer]
   (buffer->string byte-buffer (Charset/defaultCharset)))
  ([byte-buffer charset]
   (.toString (.decode charset byte-buffer))))

(defn string->buffer
  ([string]
   (string->buffer string (Charset/defaultCharset)))
  ([string charset]
   (.encode charset string)))

(defn -main
  []
  (let [server (spawn-fiber 
                 (fn [] 
                   (with-open [socket (-> (FiberServerSocketChannel/open) (.bind (InetSocketAddress. port)))
                               ^FiberSocketChannel ch (.accept socket)]
                     (let [buf (ByteBuffer/allocateDirect 1024)]
                       (.read ch buf)
                       (.flip buf)
                       (println "Server got request:" (buffer->string buf))
                       (.write ch (string->buffer "my response"))))))
        client (spawn-fiber 
                  (fn [] 
                    (with-open [^FiberSocketChannel ch (FiberSocketChannel/open (InetSocketAddress. port))]
                      (let [buf (ByteBuffer/allocateDirect 1024)]
                        (println "Client sending request")
                        (.write ch (string->buffer "a request"))
                        (.read ch buf)
                        (.flip buf)
                        (println "Client got response:" (buffer->string buf))))))]
    (join server)
    (join client)))
