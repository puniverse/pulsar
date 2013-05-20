---
layout: default
title: Examples
weight: 10
---

{% capture examples %}https://github.com/{{site.github}}/tree/master/src/test/clojure/co/paralleluniverse/pulsar_test/examples{% endcapture %}

The Pulsar source code contains several examples:

* [A Pulsar port]({{examples}}/pingpong.clj) of the canonical [Erlang ping-pong example](http://www.erlang.org/doc/getting_started/conc_prog.html#id67347)
* [A Pulsar port]({{examples}}/priority.clj) of [this example](http://learnyousomeerlang.com/more-on-multiprocessing#selective-receives) from the book *[Learn You Some Erlang for great good!](http://learnyousomeerlang.com/)*
* [A Pulsar ring benchmark]({{examples}}/ring_benchmark.clj) with actors.
* [A Pulsar ring benchmark]({{examples}}/primitive_ring_benchmark.clj) with promitive channels.
* [A Pulsar full-graph benchmark]({{examples}}/graph.clj) where all actors ping and pong with all other actors.

In addition, the [test suite](https://github.com/{{site.github}}/blob/master/src/test/clojure/co/paralleluniverse/pulsar_test.clj) contains many more small examples.