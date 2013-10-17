(defproject co.paralleluniverse/pulsar "0.4.0-SNAPSHOT"
  :description "A Clojure lightweight thread, asynchronous programming, and actor library"
  :url "http://github.com/puniverse/pulsar"
  :licenses [{:name "Eclipse Public License - v 1.0" :url "http://www.eclipse.org/legal/epl-v10.html"}
             {:name "GNU Lesser General Public License - v 3" :url "http://www.gnu.org/licenses/lgpl.html"}]
  :distribution :repo
  :source-paths      ["src/main/clojure"]
  :test-paths        ["src/test/clojure"]
  :resource-paths    ["src/main/resources"]
  :java-source-paths ["src/main/java"]
  :javac-options     ["-target" "1.7" "-source" "1.7"]
  :repositories {"snapshots" "https://oss.sonatype.org/content/repositories/snapshots"
                 "releases" "https://oss.sonatype.org/content/repositories/releases"}
  :test-selectors {:selected :selected}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [co.paralleluniverse/quasar-core   "0.3.0"]
                 [co.paralleluniverse/quasar-actors "0.3.0"]
                 [org.clojure/core.match "0.2.0"]
                 [useful "0.8.8"]
                 [gloss "0.2.2" :exclusions [com.yammer.metrics/metrics-core useful]]
                 [org.clojure/core.typed "0.1.18" :exclusions [org.apache.ant/ant org.clojure/core.unify]]]
  :manifest {"Premain-Class" "co.paralleluniverse.fibers.instrument.JavaAgent"
             "Can-Retransform-Classes" "true"}
  :jvm-opts ["-server"
             ;; ForkJoin wants these:
             "-XX:-UseBiasedLocking"
             "-XX:+UseCondCardMark"]
  :java-agents [[co.paralleluniverse/quasar-core "0.3.0"]] ; :options "v"
  :pedantic :warn
  :profiles {;; ----------- dev --------------------------------------
             :dev
             {:plugins [[lein-midje "3.1.1"]]
              :dependencies [[midje "1.5.1" :exclusions [org.clojure/tools.namespace]]]
              :jvm-opts [;; Debugging
                         "-ea"
                         ;"-Dco.paralleluniverse.lwthreads.verifyInstrumentation=true"
                         ;; Recording
                         ; "-Dco.paralleluniverse.debugMode=true"
                         ; "-Dco.paralleluniverse.globalFlightRecorder=true"
                         ; "-Dco.paralleluniverse.monitoring.flightRecorderLevel=2"
                         ; "-Dco.paralleluniverse.flightRecorderDumpFile=pulsar.log"
                         ;; Logging
                         "-Dlog4j.configurationFile=log4j.xml"
                         "-DLog4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector"
                         ]
              :global-vars {*warn-on-reflection* true}}
             
             ;; ----------- cluster --------------------------------------
             :cluster
             {:repositories {"oracle" "http://download.oracle.com/maven/"}
              :dependencies [[co.paralleluniverse/quasar-galaxy "0.3.0"]]
              :java-source-paths ["src/cluster/java"]
              :jvm-opts [;; Debugging
                         "-ea"
                         ;; Galaxy
                         "-Djgroups.bind_addr=127.0.0.1"
                         ; "-Dgalaxy.nodeId=1"
                         ; "-Dgalaxy.port=7051"
                         ; "-Dgalaxy.slave_port=8051"
                         "-Dgalaxy.multicast.address=225.0.0.1"
                         "-Dgalaxy.multicast.port=7050"
                         "-Dco.paralleluniverse.galaxy.configFile=src/test/clojure/co/paralleluniverse/pulsar/examples/cluster/config/peer.xml"
                         "-Dco.paralleluniverse.galaxy.autoGoOnline=true"
                         ;; Logging
                         "-Dlog4j.configurationFile=log4j.xml"
                         ;"-DLog4jContextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector"
                         ]}
             
             ;; ----------- doc --------------------------------------
             :doc
             {:plugins [[lein-midje "3.1.1"]
                        [codox "0.6.4"]
                        [lein-marginalia "0.7.1"]]
              :dependencies [[midje "1.5.1"]]
              :exclusions [org.clojure/tools.namespace]
              :injections [(require 'clojure.test)
                           (alter-var-root #'clojure.test/*load-tests* (constantly false))]
              :codox {:include [co.paralleluniverse.pulsar.core 
                                co.paralleluniverse.pulsar.actors
                                co.paralleluniverse.pulsar.lazyseq
                                co.paralleluniverse.pulsar.async]
                      :output-dir "docs/api"}
              :global-vars {*warn-on-reflection* false}}})
