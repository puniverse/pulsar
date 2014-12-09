---
layout: default
title: Pulsar
description: "Lightweight threads, CSP and Erlang-like actors for Clojure"
---

# Overview

Pulsar is a Clojure library that provides high-performance lightweight threads and Erlang-like actors.
It is a Clojure API for the [Quasar] Java library, with the addition of pattern matching and an Erlang-like syntax.

A good introduction to Pulsar (and Quasar) can be found in the blog post [Erlang (and Go) in Clojure (and Java), Lightweight Threads, Channels and Actors for the JVM](http://blog.paralleluniverse.co/post/49445260575/quasar-pulsar).

Pulsar and Quasar are developed by [Parallel Universe] and released as free software, dual-licensed under the Eclipse Public License and the GNU Lesser General Public License.

[Quasar]: https://github.com/puniverse/quasar
[Parallel Universe]: http://paralleluniverse.co

### Dependencies

Aside from Pulsar's dependency on Quasar and its dependent libraries, Pulsar makes use of the following open-source projects:

* [core.match](https://github.com/clojure/core.match) - A pattern matching library for Clojure.
* [Gloss](https://github.com/ztellman/gloss), by Zach Tellman - a byte-format DSL

## News

### September 23, 2014

Pulsar [0.6.1](https://github.com/puniverse/pulsar/releases/tag/v0.6.1) has been released.

### July 23, 2014

Pulsar [0.6.0](https://github.com/puniverse/pulsar/releases/tag/v0.6.0) has been released.

### March, 2014

Pulsar [0.5.0](https://github.com/puniverse/pulsar/releases/tag/v0.5.0) has been released.

### January 22, 2014

Pulsar [0.4.0](https://github.com/puniverse/pulsar/releases/tag/v0.4.0) has been released.

### July 26, 2013

[Distributed actors](http://blog.paralleluniverse.co/post/56519815799/distributed-actors-in-java-and-clojure) in Pulsar.

### July 19, 2013

Quasar/Pulsar 0.2.0 [has been released](http://blog.paralleluniverse.co/post/55876031297/quasar-pulsar-0-2-0-distributed-actors-supervisors).

### May 2, 2013

Introductory blog post: [Erlang (and Go) in Clojure (and Java), Lightweight Threads, Channels and Actors for the JVM](<http://blog.paralleluniverse.co/post/49445260575/quasar-pulsar>).

# Getting Started

### System Requirements

Java 7 and Clojure 1.5 are required to run Pulsar.

### Using Leiningen {#lein}

Add the following dependency to [Leiningen](http://github.com/technomancy/leiningen/)'s project.clj:

~~~ clojure
[co.paralleluniverse/pulsar "{{site.version}}"]
~~~

Then, the following must be added to the project.clj file:

~~~ clojure
:java-agents [[co.paralleluniverse/quasar-core "{{site.version}}"]]
~~~

or, add the following to the java command line:

~~~ sh
-javaagent:path-to-quasar-jar.jar
~~~


[Leiningen]: http://github.com/technomancy/leiningen/

### Building Pulsar {#build}

Clone the repository:

    git clone git://github.com/puniverse/pulsar.git pulsar

and run:

    lein midje

To build the documentation, you need to have [Jekyll] installed. Then run:

    jekyll build

To generate the API documentation run

    lein doc


You can run the examples like this:


    lein -o run -m co.paralleluniverse.pulsar.examples.pingpong


For benchmarks, you should use `lein trampoline`, like so:


    lein trampoline run -m co.paralleluniverse.pulsar.examples.ring-benchmark 1000 1000


[Jekyll]: http://jekyllrb.com/

# User Manual

## Pulsar Core

{% capture examples %}https://github.com/{{site.github}}/tree/master/src/test/clojure/co/paralleluniverse/pulsar/examples{% endcapture %}

### Quasar and Pulsar

Pulsar is a Clojure API to [Quasar]. Many of the concepts explained below are actually implemented in Quasar.

[Quasar]: https://github.com/puniverse/quasar


### Fibers {#fibers}

Fibers are lightweight threads. They provide functionality similar to threads, and a similar API, but they're not managed by the OS. They are lightweight (an idle fiber occupies ~400 bytes of RAM), and you can have millions of them in an application. If you are familiar with Go, fibers are like goroutines. Fibers in Pulsar (well, Quasar, actually) are scheduled by one or more [ForkJoinPool](http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/ForkJoinPool.html)s.

One significant difference between Fibers and Threads is that Fibers are not preempted; i.e. a fiber is (permanently or temporarily) unscheduled by the scheduler only if it terminates, or if it calls one of a few specific Java methods that cause the fiber to become suspended. A function that calls a suspending operation is called a *suspendable* function, and a function that calls another suspendable function is itself suspendable.

Suspendable functions require special bytecode instrumentation (performed by an instrumentation agent), so they must be explicitly designated as such.
The function `suspendable!` marks a given function as a suspendable function (this operation cannot be undone). The `defsfn` macro, with the same syntax as `defn` defines a suspendable function.

{:.alert .alert-info}
**Note**: All functions (i.e. `fn`s) passed to any of the Pulsar API functions and macros are automatically made suspendable, so in most simple cases you will never need to use `sfn`, `defsfn` or `suspendable!`.

{% comment %}
{:.alert .alert-warn}
**Note**: One thing to watch out for in suspendable functions is lazy-seqs. If the function consumes a seq, it's probably a good idea to force it with `doall` before passing it to the function.
{% endcomment %}

#### Spawning Fibers

To create a fiber of a function `f` that takes arguments `arg1` and `arg2`, run

~~~ clojure
(spawn-fiber f arg1 arg2)
~~~

`spawn-fiber` automatically marks `f` as suspendable, so there's no need to do so explicitly.

`spawn-fiber` takes optional keyword arguments:

* `:name` - The fiber's name.
* `:fj-pool` - The `ForkJoinPool` in which the fiber will run.
  If `:fj-pool` is not specified, then the pool used will be either the pool of the fiber calling `spawn-fiber`, or, if `spawn-fiber` is not called from within a fiber, a default pool.
* `:stack-size` - The initial fiber data stack size.

The fiber will terminate when `f` completes execution.

{:.alert .alert-info}
**Note**: Spawning a fiber is a very cheap operation in both computation and memory. Do not fear creating many (thousands, tens-of-thousands or even hundereds-of-thousands) fibers.

#### Joining Fibers

To wait for the fiber's termination, use

~~~ clojure
(join fiber)
~~~

If `f` returns a value, `join` will return that value. If `f` throws an exception, `join` will throw that exception.

You can also wait for a fiber's termination for a given duration. The following will wait for half a second for the fiber to terminate:

~~~ clojure
(join 500 java.util.concurrent.TimeUnit/MILLISECONDS fiber)
~~~

The following will have the same effect:

~~~ clojure
(join 500 :ms fiber)
~~~

#### Bindings

Fibers behave well with Clojure `bindings`. A newly spawned fiber inherits the bindings in effect at the time of spawning,
and bindings decleared in a fiber last throughout the fiber's lifetime. This is demonstrated in the following tests taken
from the Pulsar test suite:

~~~ clojure
(def ^:dynamic *foo* 40)

(facts "fiber-bindings"
      (fact "Fiber inherits thread bindings"
            (let [fiber
                  (binding [*foo* 20]
                    (spawn-fiber
                     #(let [v1 *foo*]
                        (Fiber/sleep 200)
                        (let [v2 *foo*]
                          (+ v1 v2)))))]
              (join fiber))
            => 40)
      (fact "Bindings declared in fiber last throughout fiber lifetime"
            (let [fiber
                  (spawn-fiber
                   #(binding [*foo* 15]
                      (let [v1 *foo*]
                        (Fiber/sleep 200)
                        (let [v2 *foo*]
                          (+ v1 v2)))))]
              (join fiber))
            => 30))
~~~

#### Compatibility with Clojure Concurrency Constructs

Code running in fibers may make free use of Clojure atoms and agents.

Spawning or dereferencing a future created with `clojure.core/future` is ok, but there's a better alternative: you can turn a spawned fiber into a future with `fiber->future` and can then dereference or call regular future functions on the returned value, like `realized?` (In fact, you don't even have to call `fiber->future`; fibers already implement the `Future` interface and can be treated as futures directly, but this may change in the future, so, until the API is fully settled, we recommend using `fiber->future`).

Running a `dosync` block inside a fiber is discouraged as it uses locks internally, but your mileage may vary.

Promises are supported and encouraged, but you should not make use of `clojure.core/promise` to create a promise that's to be dereferenced in a fiber. Pulsar provides a different -- yet completely compatible -- form of promises, as you'll see soon.

### Transforming any Asynchronous Callback to A Fiber-Blocking Operation

Fibers are great as a replacement for callbacks. The `await` macro helps us easily turn any callback-based asynchronous operation to as simple fiber-blocking call. `await` assumes that an asynchronous function takes a callback of a single argument as its last parameter; `await` then blocks the current fiber until the callback is called, and the returns the value passed to the callback.

Here's an example from the tests:

~~~ clojure
(let [exec (java.util.concurrent.Executors/newSingleThreadExecutor)
      service (fn [a b clbk] ; an asynchronous service
                  (.execute exec ^Runnable (fn []
                                              (sleep 50)
                                              (clbk (+ a b)))))
      fiber (spawn-fiber
              (fn []
                (await service 2 5)))]
   (join fiber)) ; => 7
~~~

#### Strands

Before we continue, one more bit of nomenclature: a single flow of execution in Quasar/Pulsar is called a *strand*. To put it more simply, a strand is either a normal JVM thread, or a fiber.

The strand abstraction helps you write code that works whether it runs in a fiber or not. For example, `(Strand/currentStrand)` returns the current fiber, if called from a fiber, or the current thread, if not. `(Strand/sleep millis)` suspends the current strand for a given number of milliseconds whether it's a fiber or a normal thread. Also, `join` works for both fibers and threads (although for threads `join` will always return `nil`).

### Promises, Promises

Promises, also known as dataflow variables, are an especially effective, and simple, concurrency primitive.

A promise is a value that may only be set once, but read multiple times. If the promise is read before the value is set, the reading strand will block until the promise has been set, and then return its value.

Promises are defined in `clojure.core`, but `...pulsar.core` provides its own, fully compatible version.

A promise is created simply like this:

~~~ clojure
(promise)
~~~

And is set with `deliver`. It can be read by dereferencing it with `@`, and you can test whether it's been set with `realized?` (other than the `promise` function itself, all other functions, like `deliver` and `realized` are those defined in `clojure.core`)

The `promise` function defined in Pulsar creates a promise, that, when dereferenced within a fiber, simply blocks the fiber and not the entire OS thread it's running in.

Here's an example of using promises from the tests:

~~~ clojure
(let [v1 (promise)
     v2 (promise)
     v3 (promise)
     v4 (promise)
     f1 (spawn-fiber  #(deliver v2 (+ @v1 1)))
     t1 (spawn-thread #(deliver v3 (+ @v1 @v2)))
     f2 (spawn-fiber  #(deliver v4 (+ @v3 @v2)))]
 (Strand/sleep 50)
 (deliver v1 1)
 @v4) ; => 5
~~~

This example shows how promises are set, and read, by both fibers and regular threads.

But Pulsar's promises have one additional, quite nifty, feature. If you pass an optional function to `promise`, a new fiber running that function will be spawned, and the promise will receive the value returned from the function. Here's another example from the tests:

~~~ clojure
(let [v0 (promise)
     v1 (promise)
     v2 (promise #(+ @v1 1))
     v3 (promise #(+ @v1 @v2))
     v4 (promise #(* (+ @v3 @v2) @v0))]
 (Strand/sleep 50)
 (deliver v1 1)
 (mapv realized? [v0 v1 v2 v3 v4]) ; => [false true true true false]
 (deliver v0 2)
 @v4) ; => 10
~~~

### Channels {#channels}

Channels are queues used to pass messages between strands (remember, strands are a general name for threads and fibers). If you are familiar with Go, Pulsar channels are like Go channels. The call

~~~ clojure
(channel)
~~~

creates and returns a new channel.

A more general form of the `channel` function is:

~~~ clojure
(channel capacity overflow-policy)
~~~

The channel's `capacity` is the number of messages that can wait in the queue. A positive integer creates a bounded queue that can hold up to the given number of messages until they're consumed. A capacity of -1 specifies an unbounded channel (unlimited number of pending messages), and a capacity of 0 specifies a *transfer channel*, one where the producer is blocked until a consumer requests a message and vice-versa.

`overflow-policy` specifies what happens to the producer (sender) of a message when the channel's capacity is exhausted, and may be one of:

* `:throw` - throws an exception
* `:drop` - silently drops (discards) the message
* `:block` - blocks the sender until messages are consumed from the channel and it has remaining capacity
* `:displace` - removes the oldest message waiting in the channel to make room for the new message.

If you leave out the `overflow-policy` argument, the default policy of `:block` is used. Leaving both out (and simply calling `(channel)` is the same as `(channel 0 :block)` (obviously, a transfer channel (a channel of capacity 0), would only work with a `:block` policy).

Bounded channels are generally faster than unbounded channels.

Use of the `:displace` policy places an additional restriction on the channel: its messages may be consumed by a single strand only.

#### Sending and receiving messages

Sending a message to a channel is simple:

~~~ clojure
(snd channel message)
~~~

`message` can be any object, but not `nil`. Receiving a message from a channel is equaly easy:

~~~ clojure
(rcv channel)
~~~

The `rcv` function returns the first message in the channel (the one that has waited there the longest), if there is one. If the channel is empty, the function will block until a message is sent to the channel, and will then return it.

{:.alert .alert-info}
**Note**: `rcv` is a suspendable function, so any function calling it must also be decalred suspendable. But remember, the function passed to `spawn-fiber` is automatically made suspendable.

It is also possible to limit the amount of time `rcv` will wait for a message:

~~~ clojure
(rcv channel 10 java.util.concurrent.TimeUnit/MILLISECONDS)
~~~

or, equivalently:

~~~ clojure
(rcv channel 10 :ms)
~~~

These calls will wait for a message for 10 milliseconds before giving up and returning `nil`.

#### Closing the channel

After calling

~~~ clojure
(close! channel)
~~~

any future messages sent to the channel will be ignored. Any messages already in the channel will be received. Once the last message has been received, another call to `rcv` will return `nil`.

#### Channel Selection – `sel` and `select`

A powerful tool when working with channels is the ability to wait on a message from several channels at once.

The `sel` function takes a collection containing *channel operation descriptors*. A descriptor is either a channel or a pair (vector) of a channel and a message. Each channel in the sequence represents a `rcv` attempt, and each channel-message pair represents a `snd` attempt. The `sel` function performs at most one operation on the sequence, a `rcv` or a `snd`, which is determined by the first operation that can succeed. If no operation can be carried out immediately, `sel` will block until an operation can be performed.

For example, in the following call,

~~~ clojure
(sel [ch1 [ch2 msg1] ch3 [ch4 msg2]])
~~~

a message will either be received from `ch1` or `ch2`, or one will be sent to eiter `ch2` or `ch4`. If, for instance, `ch2` will become available for reading (i.e. it has been sent a message) first, than only that operation, in this case a `rcv` will be performed on `ch1`. If `ch2` becomes available for writing before that happens, then only that operation, a `snd`, will be performed. If two operations are available at the same time, one will be chosen randomly (unless the `:priority` option is set, as we'll see later).

Note that if a channel's overflow policy is anything by `:block`, then `snd` operations are always available.

The general form of the `sel` function is

~~~ clojure
(sel ports & opts)
~~~

`sel` returns a vector of two values describing the single operation that has been performed. The first is the message received if the operation is a `rcv`, or `nil` if it's a `snd`; the second is the channel on which the operation has been performed.

The `sel` function takes two options. If `:priority` is set to `true` (thus: `:priority true`), then if more than one operation becomes available at the same time, then the one that's listed earlier in the channels collection will be performed.

The second option is `:timeout`, which takes an integer argument specifying the timeout in milliseconds. If the timeout elapses without any of the operations succeeding, `sel` will return `nil`. If the timeout value is `0`, then `sel` will never block. It will attempt to perform any of the requested operations, but if none are *immediately* available, it will return `nil`.

So, for example, calling

~~~ clojure
(sel [ch1 ch2 ch3] :timeout 0)
~~~

Will return, `[msg ch]` if any of the channels was immediately available for a `rcv`, or `nil` if none of them were.

The `select` macro performs a very similar operation as `sel`, but allows you to specify an action to perform depending on which operation has succeeded.
It takes an even number of expressions, ordered as (ops1, action1, ops2, action2 ...) with the ops being a channel operation descriptior (remember: a descriptor is either a channel for an `rcv` operation, or a vector of a channel and a message specifying a `snd` operation) or a collection of descriptors, and the actions are Clojure expressions. Like `sel`, `select` performs at most one operation, in which case it will run the operation's respective action and return its result.

An action expression can bind values to the operations results. The action expression may begin with a vector of one or two symbols. In that case, the first symbol will be bound to the message returned from the successful receive in the respective ops clause (or `nil` if the successful operation is a `snd`), and the second symbol, if present, will be bound to the successful operation's channel.

Like `sel`, `select` blocks until an operation succeeds, or, if a `:timeout` option is specified, until the timeout (in milliseconds) elapses. If a timeout is specfied and elapses, `select` will run the action in an optional `:else` clause and return its result, or, if an `:else` clause is not present, `select` will return `nil`.

Here's an example:

~~~ clojure
(select :timeout 100
        c1 ([v] (println "received" v))
        [[c2 m2] [c3 m3]] ([v c] (println "sent to" c))
        :else "timeout!")
~~~

In the example, if a message is received from channel `c1`, then it will be printed. If a message is sent to either `c2` or `c3`, then the identity of the channel will be printed, and if the 100 ms timeout elapses then "timeout!" will be printed.

Finally, just like `sel`, you can pass `:priority true` to `select`, in which case if more than one operation is available, the first one among them as listed in the `select` statement will be performed.

#### Topics

A topic is a send-port (a channel you can send to but not receive from), that broadcasts any message written to it to a number of *subscriber* channels.

A topic is created simply with:

~~~ clojure
(topic)
~~~

When a channel *subscribes* to the topic, it will receive all messages sent to the topic:

~~~ clojure
(subscribe! tpc ch)
~~~

You can also unsubscribe a channel:

~~~ clojure
(subscribe! tpc ch)
~~~

Note that a messages sent to the topic is essentialy replicated to all subscribers, i.e. it will be received once in each channel.

#### Ticker Channels

A channel created with the `:displace` policy is called a *ticker channel* because it provides guarantees similar to that of a digital stock-ticker: you can start watching at any time, the messages you read are always read in order, but because of the limited screen size, if you look away or read to slowly you may miss some messages.

The ticker channel is useful when a program component continually broadcasts some information. The size channel's circular buffer, its "screen" if you like, gives the subscribers some leeway if they occasionally fall behind reading.

As mentioned earlier, a ticker channel is single-consumer, i.e. only one strand is allowed to consume messages from the channel. On the other hand, it is possible, and useful, to create several views of the channel, each used by a different consumer strand. A view is created thus:

~~~ clojure
(ticker-consumer ch)
~~~

`ch` must be a channel of bounded capacity with the `:displace` policy. `ticker-consumer` returns a *receive port* (a channel that can only receive messages, not send them) that can be used to receive messages from `ch`. Each ticker-consumer will yield monotonic messages, namely no message will be received more than once, and the messages will be received in the order they're sent, but if the consumer is too slow, messages could be lost.

Each consumer strand will use its own `ticker-consumer`, and each can consume messages at its own pace, and each `ticker-consumer` port will return the same messages (messages consumed from one will not be removed from the other views), subject possibly to different messages being missed by different consumers depending on their pace.


#### Primitive channels

It is also possible to create channels that carry messages of primitive JVM types. The analogous primitive channel functions to `channel`, `snd` and `rcv`, are, respectively:

* `int` channels: `int-channel`, `snd-int`, `rcv-int`
* `long` channels: `long-channel`, `snd-long`, `rcv-long`
* `float` channels: `float-channel`, `snd-float`, `rcv-float`
* `double` channels: `double-channel`, `snd-double`, `rcv-double`

Because they don't require boxing (for this reason `snd-xxx` and `rcv-xxx` are actually macros), primitive channels can provide better performance than regular channels. Primitive channels, however, are single-consumer, namely, only a single strand may read messages from any given channel.

Calling `rcv-xxx` on a closed channel will throw an exception.

{% comment %}
#### Channel lazy-seqs

{:.centered .alert .alert-warning}
**Note**: Channel lazy-seqs are an experimental feature.

Messages received through channels can be manipulted with Clojure's sequence manipulation functions by transforming a channel into a `lazy-seq`. Unfortunately, Clojure implements `lazy-seq`s with the `clojure.lang.LazySeq` class, which *almost* allows a `lazy-seq` implementation by a channel - but not quite (a simple reordering of a couple of Java lines in the `LazySeq` class would have made it compatible with channels). The `lazy-seq` function in the `co.paralleluniverse.pulsar.lazyseq` namespace therefore creates a `lazy-seq` that works just like Clojure's, but uses a slightly different underlying Java implementation. This is unfortunate, because it required a re-implementation of many sequence manipulation functions.

Nevertheless, it's still possible to work with channels as you would with lazy-seqs, you only need to mind calling the functions in the right namespace.

To create a lazy-seq from a channel, simply call:

~~~ clojure
(channel->lazy-seq ch)
~~~

Then, manipulating messages with sequence functions is easy. Here are some examples from the tests:

~~~ clojure
(require [co.paralleluniverse.pulsar.lazyseq :as s :refer [channel->lazy-seq snd-seq]])

(facts "channels-seq"
       (fact "Receive sequence with sleep"
             (let [ch (channel)
                   fiber (spawn-fiber
                          (fn []
                            (let [s (s/take 5 (channel->lazy-seq ch))]
                              (s/doall s))))]
               (dotimes [m 10]
                 (Thread/sleep 20)
                 (snd ch m))
               (join fiber))  => (list 0 1 2 3 4))
       (fact "Map received sequence with sleep"
             (let [ch (channel)
                   fiber (spawn-fiber (fn [] (s/doall (s/map #(* % %) (s/take 5 (channel->lazy-seq ch))))))]
               (dotimes [m 10]
                 (Thread/sleep 20)
                 (snd ch m))
               (join fiber)) => (list 0 1 4 9 16))
       (fact "Filter received sequence with sleep (odd)"
             (let [ch (channel)
                   fiber (spawn-fiber
                          #(s/doall (s/filter odd? (s/take 5 (channel->lazy-seq ch)))))]
               (dotimes [m 10]
                 (Thread/sleep 20)
                 (snd ch m))
               (join fiber)) => (list 1 3))
       (fact "Filter and map received sequence with sleep (even)"
             (let [ch (channel)
                   fiber (spawn-fiber
                          (fn [] (s/doall
                                  (s/filter #(> % 10)
                                            (s/map #(* % %)
                                                   (s/filter even?
                                                             (s/take 5 (channel->lazy-seq ch))))))))]
               (dotimes [m 10]
                 (Thread/sleep 20)
                 (snd ch m))
               (join fiber)) => (list 16)))
~~~
{% endcomment %}

### Channel Transformation (AKA Reactive Extensions)

The `co.paralleluniverse.pulsar.rx` namespace contains functions for transforming and combining channels. Known as "reactive extensions", these transformations let you model your computation as a flow of data. These are the supported transformations:

* map - returns a channel that transforms messages by applying a given mapping function. There are two versions of this operation: `map` which transforms messages as they are received from the channel, and `snd-map` which transforms the messages right before they are sent to the channel.
* filter - returns a channel that only lets messages that satisfy a predicate through. Like map, there are two versions of this operation: `filter`, which filters messages as they are received from the channel, and `snd-filter`, which filters them right before they are sent. Either operation drops the messages that do not satisfy the predicate, and they are lost.
* `zip` - returns a channel that combines messages from a collection of channels into a combined vector message.
* `group` - returns a channel that funnels messages from a set of given channels into one group channel.

Examples of using all channel transformations can be found in the [rx test suite](https://github.com/puniverse/pulsar/blob/master/src/test/clojure/co/paralleluniverse/pulsar/rx_test.clj).

## Pulsar's Actor System

To use the terms we've learned so far, an *actor* is a strand that owns a single channel with some added lifecycle management and error handling. But this reductionist view of actors does them little justice. Actors are fundamental building blocks that are combined to build a fault-tolerant application. If you are familiar with Erlang, Pulsar actors are just like Erlang processes.

An actor is a self-contained execution unit with well-defined inputs and outputs. Actors communicate with other actors (as well as regular program threads and fibers) by passing messages.

{:.alert .alert-info}
**Note**: Actors may write to and read from channels other than their own mailbox. In fact, actors can do whatever regular fibers can.

#### Spawning actors

Actors can run in any strand – fiber or thread - but for now, Pulsar only supports actors running in fibers (Quasar, the Java library that Pulsar wraps, allows running actors in regular threads).

An actor is basically a function that -- if the actor is to do anything interesting -- receives messages from the mailbox.
To create and start an actor of a function `f` that takes arguments `arg1` and `arg2`, run

~~~ clojure
(spawn f arg1 arg2)
~~~

This will create a new actor, and start running it in a new fiber.

`spawn` automatically marks `f` as suspendable, so there's no need to do so explicitly.

`spawn` takes optional keyword arguments:

* `:name` - The actor's name (that's also given to the fiber running the actor).
* `:mailbox-size` - The number of messages that can wait in the mailbox, or -1 (the default) for an unbounded mailbox.
* `:overflow-policy` - What to do if a bounded mailbox overflows. Can be either:
  - `:throw`, in which case an exception will be thrown *into the receiving actor*
  - `:drop`, in which case the message will be silently discarded, or
  - `:block`, in which case the sender will block until there's room in the mailbox.
* `:trap` - If set to `true`, linked actors' death will send an exit message rather than throw an exception (see below).
* `:lifecycle-handle` - A function that will be called to handle special messages sent to the actor. If set to `nil` (the default), the default handler is used, which is what you want in all circumstances, except for some actors that are meant to do some special tricks.
* `:fj-pool` - The `ForkJoinPool` in which the fiber will run.
  If `:fj-pool` is not specified, then the pool used will be either 1) the pool of the fiber calling `spawn-fiber`, or, if `spawn-fiber` is not called from within a fiber, a default pool.
* `:stack-size` - The initial fiber data stack size.

Of all the optional arguments, you'll usually only use `:name` and `:mailbox-size`+`:overflow-policy`. As mentioned, by default the mailbox is unbounded. Bounded mailboxes provide better performance and should be considered for actors that are expected to handle messages at a very high rate.

An actor can be `join`ed, just like a fiber.

{:.alert .alert-info}
**Note**: Just like fibers, spawning an actor is a very cheap operation in both computation and memory. Do not fear creating many (thousands, tens-of-thousands or even hundreds-of-thousands) actors.

### Sending and Receiving Messages

An actor's mailbox is a channel, that can be obtained with the `mailbox-of` function. You can therefore send a message to an actor like so:

~~~ clojure
(snd (mailbox-of actor) msg)
~~~

But there's an easier way. Actors implement the `SendPort` interface, and so, are treated like a channel by the `snd` function. So we can simple call:

~~~ clojure
(snd actor msg)
~~~

While the above is a perfectly valid way of sending a message to an actor, this is not how it's normally done. Instead of `snd` we normally use the `!` (bang) function to send a message to an actor, like so:

~~~ clojure
(! actor msg)
~~~

The bang operator has a slightly different semantic than `snd`. While `snd` will always place the message in the mailbox, `!` will only do it if the actor is alive. It will not place a message in the mailbox if there is no one to receive it on the other end (and never will be, as mailboxes, like all channels, cannot change ownership).

In many circumstances, an actor sends a message to another actor, and expects a reply. In those circumstances, using `!!` instead of `!` might offer reduced latency (but with the same semantics; both `!` and `!!` always return `nil`)

The value `@self`, when evaluated in an actor, returns the actor. So, as you may guess, an actor can receive messages with:

~~~ clojure
(rcv (mailbox-of @self))
~~~

`@mailbox` returns the actor's own mailbox channel, so the above may be written as:

~~~ clojure
(rcv @mailbox)
~~~

... and because actors also implement the `ReceivePort` interface required by `rcv`, the following will also work:

~~~ clojure
(rcv @self)
~~~

But, again, while an actor can be treated as a fiber with a channel, it has some extra features that give it a super-extra punch. Actors normally receive messages with the `receive` function, like so:

~~~ clojure
(receive)
~~~

`receive` has some features that make it very suitable for handling messages in actors. Its most visible feature is pattern matching. When an actor receives a message, it usually takes different action based on the type and content of the message. Making the decision with pattern matching is easy and elegant:

~~~ clojure
(let [actor (spawn
               #(receive
                   :abc "yes!"
                   [:why? answer] answer
                   :else "oy"))]
     (! actor [:why? "because!"])
     (join actor)) ; => "because!"
~~~

As we can see in the example, `receive` not only picks the action based on the message, but also destructures the message and binds free variable, in our example – the `answer` variable. `receive` uses the [core.match](https://github.com/clojure/core.match) library for pattern matching, and you can consult [its documentation](https://github.com/clojure/core.match/wiki/Overview) to learn exactly how matching works.

Sometimes, we would like to assign the whole message to a variable. We do it by creating a binding clause in `receive`:

~~~ clojure
(receive [m]
   [:foo val] (println "got foo:" val)
   :else      (println "got" m))
~~~

We can also match not on the raw message as its been received, but transform it first, and then match on the transformed value, like so, assuming `transform` is a function that takes a single argument (the message):

~~~ clojure
(receive [m transform]
   [:foo val] (println "got foo:" val)
   :else      (println "got" m))
~~~

Now `m` – and the value we're matching – is the the transformed value.

`receive` also deals with timeouts. Say we want to do something if a message has not been received within 30 milliseconds (all `receive` timeouts are specified in milliseconds):

~~~ clojure
(receive [m transform]
   [:foo val] (println "got foo:" val)
   :else      (println "got" m)
   :after 30  (println "nothing..."))
~~~

{:.alert .alert-warn}
**Note**: The `:after` clause in `receive` *must* be last.

Before we move on, it's time for a short example. In this example, we will define an actor, `adder`, that receives an `:add` message with two numbers, and reply to the sender with the sum of those two numbers. In order to reply to the sender, we need to know who the sender is. So the sender will add a reference to itself in the message. In this request-reply pattern, it is also good practice to attach a random unique tag to the request, because messages are asynchronous, and it is possible that the adder will not respond to the requests in the order they were received, and the requester might want to send two requests before waiting for a response, so a tag is a good way to match replies with their respective requests. We can generate a random tag with the `maketag` function.

Here's the adder actor:

~~~ clojure
(defsfn adder []
  (loop []
    (receive
     [from tag [:add a b]] (! from tag [:sum (+ a b)]))
    (recur)))
~~~

And this is how we'll use it from within another actor:

~~~ clojure
...
(let [tag (maketag)
      a ...
      b ...]
   (! adder-actor @self tag [:add a b])
   (->>
      (receive
         [tag [:sum sum]] sum
         :after 10        nil)
      (println "sum:"))
...
~~~

### Actors vs. Channels

One of the reasons of providing a different `receive` function for actors is because programming with actors is conceptually different from just using fibers and channels. I think of channels as hoses  pumping data into a function, or as sort of like asynchronous parameters. A fiber may pull many different kinds of data from many different channels, and combine the data in some way.

Actors are a different abstraction. They are more like objects in object-oriented languages, assigned to a single thread. The mailbox serves as the object's dispatch mechanism; it's not a hose but a switchboard. It's for this reason that actors often need to pattern-match their mailbox messages, while regular channels – each usually serving as a conduit for a single kind of data – don't.

But while the `receive` syntax is nice and all (it mirrors Erlang's syntax), we could have achieved the same with `rcv` almost as easily:

~~~ clojure
(let [m1 (rcv 30 :ms)]
   (if m1
      (let [m (transform m1)]
         (match (transform (rcv 30 :ms))
             [:foo val]  (println "got foo:" val)
   		     :else      (println "got" m)))
   	   (println "nothing...")))
~~~

Pretty syntax is not the main goal of the `receive` function. The reason `receive` is much more powerful than `rcv`, is mostly because of a feature we will now introduce.

{:.alert .alert-info}
**Note**: Because actors implement the `SendPort` interface, the `snd-map` and `snd-filter` functions (in the `rx` namespace) can be applied to actors as well.

### Selective Receive

An actor is a state machine. It usually encompasses some *state* and the messages it receives trigger *state transitions*. But because the actor has no control over which messages it receives and when (which can be a result of either other actors' behavior, or even the way the OS schedules threads), an actor would be required to process any message and any state, and build a full *state transition matrix*, namely how to transition whenever *any* messages is received at *any* state.

This can not only lead to code explosion; it can lead to bugs. The key to managing a complex state machine is by not handling messages in the order they arrive, but in the order we wish to process them. If a message does not match any of the clauses in `receive`, it will remain in the mailbox. `receive` will return only when it finds a message that does. When another `receive` statement is called, it will again search the messages that are in the mailbox, and may match a message that has been skipped by a previous `receive`.

In this code snippet, we specifically wait for the `:baz` message after receiving `:foo`, and so process the messages in this order -- `:foo`, `:baz`, `:bar` -- even though `:bar` is sent before `:baz`:

~~~ clojure
(let [res (atom [])
      actor (spawn
              #(dotimes [i 2]
                 (receive
                   [:foo x] (do
                              (swap! res conj x)
                              (receive
                                [:baz z] (swap! res conj z)))
                   [:bar y] (swap! res conj y)
                   [:baz z] (swap! res conj z))))]
  (! actor [:foo 1])
  (! actor [:bar 2])
  (! actor [:baz 3])
  (join actor)
  @res) ; => [1 3 2]
~~~

[Another example]({{examples}}/priority.clj) demonstrates receiving messages in order of priority.

Selective receive is also very useful when communicating with other actors. Here's an excerpt from [this example]({{examples}}/selective.clj):

~~~ clojure
(defsfn adder []
  (loop []
    (receive
      [from tag [:add a b]] (! from tag [:sum (+ a b)]))
    (recur)))

(defsfn computer [adder]
  (loop []
    (receive [m]
             [from tag [:compute a b c d]] (let [tag1 (maketag)]
                                             (! adder [@self tag1 [:add (* a b) (* c d)]])
                                             (receive
                                               [tag1 [:sum sum]]  (! from tag [:result sum])
                                               :after 10          (! from tag [:error "timeout!"])))
             :else (println "Unknown message: " m))
    (recur)))

(defsfn curious [nums computer]
  (when (seq nums)
    (let [[a b c d] (take 4 nums)
          tag       (maketag)]
      (! computer @self tag [:compute a b c d])
      (receive [m]
               [tag [:result res]]  (println a b c d "->" res)
               [tag [:error error]] (println "ERROR: " a b c d "->" error)
               :else (println "Unexpected message" m))
      (recur (drop 4 nums) computer))))

(defn -main []
  (let [ad (spawn adder)
        cp (spawn computer ad)
        cr (spawn curious (take 20 (repeatedly #(rand-int 10))) cp)]
    (join cr)
    :ok))
~~~

In the example, we have three actors: `curious`, `computer` and `adder`. `curious` asks `computer` to perform a computation, and `computer` relies on `adder` to perform addition. Note the nested `receive` in `computer`: the actor waits for a reply from `adder` before accepting other requests (from `curious`) in the outer receive (actually, because this pattern of sending a message to an actor and waiting for a reply is so common, it's encapsulated by a construct call `gen-server` - yet another blatant theft from Erlang - which we'll introduce later; if you want to see how this example looks using `gen-server`, take a look [here]({{examples}}/selective_gen_server.clj).

There are several actor systems that do not support selective receive, but Erlang does, and so does Pulsar. [The talk *Death by Accidental Complexity*](http://www.infoq.com/presentations/Death-by-Accidental-Complexity), by Ulf Wiger, shows how using selective receive avoids implementing a full, complicated and error-prone transition matrix. [In a different talk](http://www.infoq.com/presentations/1000-Year-old-Design-Patterns), Wiger compared non-selective (FIFO) receive to a tetris game where you must fit each piece into the puzzle as it comes, while selective receive turns the problem into a jigsaw puzzle, where you can look for a piece that you know will fit.

{:.alert .alert-warn}
**A word of caution**: Using selective receive in your code may lead to deadlocks (because you're essentially saying, I'm going to wait here until a specific message arrives). This can be easily avoided by always specifying a timeout (with the `:after millis` clause) when doing a selective receive. Selective receive is a powerful tool that can greatly help writing readable, maintainable message-handling code, but don't over-use it.

### Actor State

In Erlang, actor state is set by recursively calling the actor function with the new state as an argument. In Pulsar, we can do the same. Here’s an example:

~~~ clojure
(let [actor
      (spawn #(loop [i (int 2)
                     state (int 0)]
                (if (== i 0)
                  state
                  (recur (dec i) (+ state (int (receive)))))))]
  (! actor 13)
  (! actor 12)
  (join actor)) ; => 25
~~~

Clojure is all about managing state. It ensures that every computation has access to consistent data. Because actors communicate with other computation only by exchanging immutable messages, and because each actor runs in a single strand, it's absolutely ok for an actor to have mutable state - only the actor has access to it.

Every Pulsar actor has a `state` field that can be read like this `@state` and written with `set-state!`. Here’s an example:

~~~ clojure
(let [actor
      (spawn #(do
                (set-state! 0)
                (set-state! (+ @state (receive)))
                (set-state! (+ @state (receive)))
                @state))]
  (! actor 13)
  (! actor 12)
  (join actor)) ; => 25
~~~

Finally, what if we want several state fields? What if we want some or all of them to be of a primitive type? This, too, poses no risk of race conditions because all state fields are written and read only by the actor, and there is no danger of them appearing inconsistent to an observer.
Pulsar supports this as an experimental feature (implemented internally with `deftype`), like so:

~~~ clojure
(let [actor (spawn (actor [^int sum 0]
                          (set! sum (int (+ sum (receive))))
                          (set! sum (int (+ sum (receive))))
                          sum))]
  (! actor 13)
  (! actor 12)
  (join actor)) ; => 25
~~~

These are three different ways of managing actor state. Eventually, we’ll settle on just one or two (and are open to discussion about which is preferred).

### State Machines with Strampoline

As we've seen, the `receive` form defines which messages the actor is willing to accept and process. You can nest `receive` statements, or place them in other functions that the actor calls (in which case the must be defined with `defsfn`). It is often useful to treat the actor as a state machine, going from one state to another, executing a different `receive` at each state (to define the acceptable transitions from the state). To change state, all we would have to do is call a different function, each with its own receive, but here we face a technical limitation of Clojure. As Clojure (due to JVM limitations) does not perform true tail-call optimization, every state transition (i.e. every function call), would add a frame to the stack, eventually throwing a stack overflow. Clojure solves it with the `clojure.core/trampoline` function. It takes a function and calls it. When the function returns, if the returned value is a function, `trampoline` calls it.

Pulsar comes with a version of `trampoline` for suspendable functions called `strampoline` (with the exact same API as `trampoline`).

Consider this example:

~~~ clojure
(let [state2 (sfn []
                    (receive
                      :bar :foobar))
      state1 (sfn []
                    (receive
                      :foo state2))
      actor (spawn (fn []
                     (strampoline state1)))]
  (! actor :foo)
  (Thread/sleep 50) ; or (Strand/sleep 50)
  (! actor :bar)
  (join actor)) ; => :foobar
~~~

The actor starts at `state1` (represented by the function with the same name), by calling `(strampoline state1)`. In `state1` we expect to receive the message `:foo`. When it arrives, we transition to `state2` by returning the `state2` function (which will immediately be called by `strampoline`). In `state1` we await the `:bar` message, and then terminate.

What happens if the messages `:foo` and `:bar` arrive in reverse order? Thanks to selective receive the result will be exactly the same! `state1` will skip the `:bar` message, and transition to `state2` when `:foo` arrives; the `receive` statement in `state2` will then find the `:bar` message waiting in the mailbox:

~~~ clojure
(let [state2 (sfn []
                    (receive
                      :bar :foobar))
      state1 (sfn []
                    (receive
                      :foo state2))
      actor (spawn (fn []
                     (strampoline state1)))]
  (! actor :bar)
  (Thread/sleep 50) ; or (Strand/sleep 50)
  (! actor :foo)
  (join actor)) ; => :foobar
~~~

### Error Handling

The actor model does not only make concurrency easy; it also helps build fault-tolerant systems by compartmentalizing failure. Each actor is it's own execution context - if it encounters an exception, only the actor is directly affected (like a thread, only actors are lightweight). Unlike regular functions/objects, where an exception has to be caught and handled immediately on the callstack, with actors we can completely separate code execution from error handling.

In fact, when using actors, it is often best to to follow the [philosophy laid out by Joe Armstrong](http://www.erlang.org/download/armstrong_thesis_2003.pdf), Erlang's chief designer, of "let it crash". The idea is not to try and catch exceptions inside an actor, because attempting to catch and handle all exceptions is futile. Instead, we just let the actor crash, monitor its death elsewhere, and then take some action.

The principle of actor error handling is that an actor can be asked to be notified of another actor's death. This is done through *linking* and *watching*.

#### Linking actors

You link two actors with the `link!` function like this:

~~~ clojure
(link! actor1 actor2)
~~~

Better yet, is to call the function from within one of the actors, say `actor1`, in which case it will be called like so:

~~~ clojure
(link! actor2)
~~~

A link is symmetrical. When two actors are linked, when one of them dies, the other throws an exception which, unless caught, kills it as well.

Here's an example from the tests:

~~~ clojure
(let [actor1 (spawn #(Fiber/sleep 100))
      actor2 (spawn
               (fn []
                 (link! actor1)
                 (try
                   (loop [] (receive [m] :foo :bar) (recur))
                   (catch co.paralleluniverse.actors.LifecycleException e
                     true))))]

  (join actor1)
  (join actor2)) ; => true
~~~

Remember, linking is symmetrical, so if `actor2` were to die, `actor1` would get the exception.

What if `actor2` wants to be notified when `actor1` dies, but doesn't want to die itself? The `:trap` flag for the `spawn` macro, tells is to trap lifecycle exceptions and turn them into messages:

~~~ clojure
(let [actor1 (spawn #(Strand/sleep 100))
      actor2 (spawn :trap true
                    (fn []
                      (link! actor1)
                      (receive [m]
                               [:exit _ actor reason] actor)))]
  (join actor1)
  (join actor2)) ; => actor1
~~~

Now, when `actor1` dies, `actor2` receives an `:exit` message, telling it which actor has died and how. We'll look into the `:exit` message in a second.

We can undo the link by calling

~~~ clojure
(unlink! actor1 actor2)
~~~

or

~~~ clojure
(unlink! actor2)
~~~

from within `actor1`.

#### Watching actors

A more robust way of being notified of actor death than linking is with a *watch* (called *monitor* in Erlang; this is one of the very few occasions we have abandoned the Erlang function names):

~~~ clojure
(let [actor1 (spawn #(Fiber/sleep 200))
      actor2 (spawn
               #(let [w (watch! actor1)]
                  (receive
                    [:exit w actor reason] actor)))]
  (join actor1)
  (join actor2)) ; => actor1
~~~

Watches are asymmetrical. Here, `actor2` watches for `actor1`'s death, but not vice-versa. When `actor1` dies, `actor2` gets an `:exit` message, of the exact same structure of the message sent when we used a link and a `:trap` flag.

The `watch!` function returns a watch object. Because an actor can potentially set many watches on another actor (say, it calls a library function which calls `watch!`), we could potentially get several copies of the exit message, each for a different watch.

The message is a vector of 4 elements:

1. `:exit`
2. The watch interested in the message (or `nil` when linking). Note how in the example we pattern-match on the second element (with the `w` value, which contains the watch object), to ensure that we only process the message belonging to our watch.
3. The actor that just died.
4. The dead actor's death cause: `nil` for a natural death (no exception thrown, just like in our example), or the throwable responsible for the actor's death.

We can remove a watch by calling

~~~ clojure
(unwatch! actor1 actor2)
~~~

or

~~~ clojure
(unwatch! actor2)
~~~

from within `actor1`.

### Actor Registration

*Registering* an actor gives it a public name that can be used to locate the actor. You register an actor like so:

~~~ clojure
(register! actor name)
~~~

or:

~~~ clojure
(register! actor)
~~~

in which case the name will be the one given to the actor when it was `spawn`ed. `name` can be a string, or any object with a nice string representation (like a keyword).

You obtain a reference to a registered actor with:

~~~ clojure
(whois name)
~~~

but most actor-related functions can work directly with the registered name. For example, instead of this:

~~~ clojure
(register! actor :foo)
(! (whois :foo) "hi foo!")
~~~

you can write:

~~~ clojure
(register !actor :foo)
(! :foo "hi foo!")
~~~

You unregister an actor like so:

~~~ clojure
(unregister! actor)
~~~

#### Registration and Monitoring

When you register an actor, Pulsar automatically creates a JMX MBean to monitor it. Look for it using JConsole or VisualVM.

Details TBD.

#### Registration and Clustering

If you're running in a Galaxy cluster, registering an actor will make it globally available on the cluster (so the name must be unique to the entire cluster).

Details TBD.

### Behaviors

Erlang's designers have realized that many actors follow some common patterns - like an actor that receives requests for work and then sends back a result to the requester. They've turned those patterns into actor templates, called behaviors, in order to save poeple work and avoid some common errors. Some of these behaviors have been ported to Pulsar.

Behaviors have two sides. One is the provider side, and is modeled in Pulsar as a protocols. You implement the protocol, and Pulsar provides the full actor implementation that uses your protocol. The other is the consumer side -- functions used by other actors to access the functionality provided by the behavior.

All behaviors (gen-server, gen-event and supervisors) support the `shutdown!` function, which requests an orderly shutdown of the actor:

~~~ clojure
(shutdown! behavior-actor)
~~~

#### gen-server

`gen-server` is a template for a server actor that receives requests and replies with responses. The consumer side for gen-server consists of the following functions:

~~~ clojure
(call! actor request)
~~~

This would send the `request` message to the gen-server actor, and block until a response is received. It will then return the response. If the request triggers an exception in the actor, that exception will be thrown by `call!`.

There's also a timed version of `call!`, which gives up and returns `nil` if the timeout expires. For example, :

~~~ clojure
(call-timed! actor 100 :ms request)
~~~

would wait up to 100ms for a response.

You can also send a gen-server messages that do not require a response with the `cast!` function:

~~~ clojure
(cast! actor message)
~~~

Finally, you can shutdown a gen-server with the shutdown function:

~~~ clojure
(shutdown! actor)
~~~

In order to create a gen-server actor(the provider side), you need to implement the following protocol:

~~~ clojure
(defprotocol Server
  (init [this])
  (handle-call [this ^Actor from id message])
  (handle-cast [this ^Actor from id message])
  (handle-info [this message])
  (handle-timeout [this])
  (terminate [this ^Throwable cause]))
~~~

* `init` -- will be called alled when the actor starts
* `terminate` -- will be called when the actor terminates.
* `handle-call` -- called when the `call` function has been called on the actor :). This is where the gen-server's functionality usually lies. The value returned from `handle-call` will be sent back to the actor making the request, unless `nil` is returned, in which case the response has to be sent manually as we'll see later.
* `handle-cast` -- called to handle messages sent with `cast!`.
* `handle-info` -- called whenever a message has been sent to the actor directly (i.e., with `!`) rather than through `call!` or `cast!`.
* `handle-timeout` -- called whenever the gen-server has not received any messages for a configurable duration of time. The timeout can be configured using either the `:timeout` option to the `gen-server` function, or by calling the `set-timeout!` function, as we'll immediately see.

You spawn a gen-server actor like so:

~~~ clojure
(spawn (gen-server <options?> server))
~~~

where `options` can now only be `:timeout millis`. Here's an example from the tests:

~~~ clojure
(let [gs (spawn
           (gen-server (reify Server
                         (init [_])
                         (terminate [_ cause])
                         (handle-call [_ from id [a b]]
                                      (Strand/sleep 50)
                                      (+ a b)))))]
  (call! gs 3 4); => 7
~~~

And here's one with server timeouts:

~~~ clojure
(let [times (atom 0)
            gs (spawn
                 (gen-server :timeout 20
                             (reify Server
                               (init [_])
                               (handle-timeout [_]
                                               (if (< @times 5)
                                                 (swap! times inc)
                                                 (shutdown!)))
                               (terminate [_ cause]))))]
        (join 200 :ms gs)
        @times) ; => 5
~~~

You can set (and reset) the timeout from anywhere within the protocol's methods by calling, say

~~~ clojure
(set-timeout! 100 :ms)
~~~

A timeout value of 0 or less means no timeout.

If the `handle-call` function returns `nil`, then no response is sent to the caller. The `call!` function remains blocked until a response is sent manually. This is done with the `reply!` function, which takes, along with the response message, the identitiy of the caller and the request ID, both passed to `handle-call`. Here's an example:

~~~ clojure
(let [gs (spawn
           (gen-server :timeout 50
                       (reify Server
                         (init [_]
                               (set-state! {}))
                         (terminate [_ cause])
                         (handle-call [_ from id [a b]]
                                      (set-state! (assoc @state :a a :b b :from from :id id))
                                      nil)
                         (handle-timeout [_]
                                         (let [{:keys [a b from id]} @state]
                                           (when id
                                             (reply! from id (+ a b))))))))]
  (call-timed! gs 100 :ms 5 6)) ; => 11
~~~

In the example, `handle-call` saves the request in the actor's state, and later, in `handle-timeout` sends the response using `reply!`. The response is returned by `call-timed!`.

If an error is encountered during the generation of the delayed repsonse, an exception can be returned to the caller (and will be thrown by `call!`), using `reply-error!`:

~~~ clojure
(reply-error! to id (Exception. "does not compute"))
~~~

where `to` is the identity of the caller passed as `from` to `handle-call`.

#### gen-event

gen-event is an actor behavior that receives messages (*events*) and forwards them to registered *event handlers*.

You spawn a gen-event like this:

~~~ clojure
(spawn (gen-event init))
~~~

`init` is an initializer function called from within the gen-event actor.

You can then add event handlers:

~~~~ clojure
(add-handler! ge handler)
~~~~

with `ge` being the gen-event actor (returned by the call to `spawn`), and `handler` being a function of a single argument that will be called whenever an event is generated.

You generate an event with the `notify!` function:

~~~ clojure
(notify! ge event)
~~~

with `ge` being the gen-event actor, and `event` is the event object (which can be any object). The event object is then passed to all registered event handlers.

An event handler can be removed like so:

~~~ clojure
(remove-handler! ge handler)
~~~

Here's a complete example, taken from the tests:

~~~ clojure
(let [ge (spawn (gen-event
                  #(add-handler! @self handler1)))]
  (add-handler! ge handler2)
  (notify! ge "hello"))
~~~

In this example, `handler1` is added in the `init` function (note how `@self` refers to the gen-event actor itself, as the init function is called from within the actor), and `handler2` is added later.

When `notify!` is called, both handlers will be called and passed the event object (in this case, the `"hello"` string).

### Supervisors

A supervisor is an actor behavior designed to standardize error handling. Internally it uses watches and links, but it offers a more structured, standard, and simple way to react to errors.

The general idea is that actors performing business logic, "worker actors", are supervised by a supervisor actor that detects when they die and takes one of several pre-configured actions. Supervisors may, in turn, be supervised by other supervisors, thus forming a supervision hierarchy that compartmentalizes failure and recovery.

A supervisors works as follows: it has a number of *children*, worker actors or other supervisors that are registered to be supervised wither at the supervisor's construction time or at a later time. Each child has a mode, `:permanent`, `:transient` or `:temporary` that determines whether its death will trigger the supervisor's *recovery event*. When the recovery event is triggered, the supervisor takes action specified by its *restart strategy*, or it will give up and fail, depending on predefined failure modes.

When a child actor in the `:permanent` mode dies, it will always trigger its supervisor's recovery event. When a child in the `:transient` mode dies, it will trigger a recovery event only if it has died as a result of an exception, but not if it has simply finished its operation. A `:temporary` child never triggers it supervisor's recovery event.

A supervisor's *restart strategy* determines what it does during a *recovery event*: A strategy of `:escalate` measns that the supervisor will shut down ("kill") all its surviving children and then die; a `:one-for-one` strategy will restart the dead child; an `:all-for-one` strategy will shut down all children and then restart them all; a `:rest-for-one` strategy will shut down and restart all those children added to the suervisor after the dead child.

A supervisor is spawned so:

~~~ clojure
(spawn (supervisor restart-strategy init))
~~~

where `restart-strategy` is one of: `:escalate`, `:one-for-one`, `:all-for-one`, or `:rest-for-one`, and `init` is a function that returns a sequence of *child specs* that will be used to add children to the supervisor when it's constructed.

A *child spec* is a vector of the following form:

~~~ clojure
[id mode max-restarts duration unit shutdown-deadline-millis actor-fn & actor-args]
~~~

where:

* `id` is an optional identifier (usually a string) for the child actor. May be `nil`.
* `mode` is one of `:permanent`, `:transient` or `:temporary`.
* `max-restarts`, `duration` and `unit` are a triplet specifying how many times is the child allowed to restart in a given period of time before the supervisor should give up, kill all its children and die. For example `20 5 :sec` means at most 20 restarts in 5 seconds.
* `shutdown-deadline-millis` is the maximal amount of time, in milliseconds that the child is allowed to spend from the time it's requested to shut down until the time it is terminated. Whenever a the supervisor shuts down a child, it does so by sending it the message `[:shutdown sup]`, with `sup` being the supervisor. If the shutdown deadline elapses, the supervisor will forcefully shut it down by interrupting the child's strand.
* `actor-fn & actor-args` are the (suspendable) function (with optional arguments) that's to serve as the child actor's body.

It is often useful to pass the supervisor to a child (so it could later dynamically add other children to the supervisor, for example). This is easily done because the `init` function is called inside the supervisor; therefore, any reference to `@self` inside the init function returns the supervisor. If you pass `@self`, then, as an argument to a child actor, it will receive the supervisor.

Other than returning a sequence of child specs from the `init` function, you can also dynamically add a child to a supervisor by simply calling

~~~ clojure
(add-child! sup id mode max-restarts duration unit shutdown-deadline-millis actor-fn & actor-args)
~~~

with `sup` being the supervisor, and the rest of the arguments comprising the child spec for the actor, with the difference that if `actor-fn`, instead of an actor function, is a spawned actor (the value returned from `spawn`), then supervisor will supervise an already-spawned actor. Otherwise, (if it is a function), a new actor will be spawned.

A supervised actor may be removed from the supervisor by calling

~~~ clojure
(remove-child! sup id)
~~~

with `id` being the one given to the actor in the child spec or the arguments to `add-child`.

### Hot Code Swapping

Hot code swapping is the ability to change your program's code while it is running, with no need for a restart. Pulsar actors support a limited form of hot code swapping. Hot code swapping in Pulsar generally entails rebinding or redefining vars.

An example of hot code swapping is found in the [codeswap.clj](https://github.com/puniverse/pulsar/blob/master/src/test/clojure/co/paralleluniverse/pulsar/examples/codeswap.clj) program.

#### Swapping plain actors

In the actor's main loop, using the `recur-swap` macro rather than `recur`, would use the new definition of the actor function, if one is found. The only difference in syntax between `recur-swap` and `recur` is that `recur-swap` takes the name of the function is the first parameter.

In the following example (taken from [codeswap.clj](https://github.com/puniverse/pulsar/blob/master/src/test/clojure/co/paralleluniverse/pulsar/examples/codeswap.clj)) if `a` is redefined, its new definition will be used in the next iteration.

~~~ clojure
(defsfn a [n]
    (when-let [m (receive-timed 1000)]
        (println "message:" m))
    (recur-swap a (inc n)))
~~~

#### Swapping gen-server

`gen-server`s don't need any special action to support hot code swapping. If the implementation of the `Server` protocol passed to `gen-server` is a var and that var is redefined, the new definition will be used when processing the next request. This is an example (taken from [codeswap.clj](https://github.com/puniverse/pulsar/blob/master/src/test/clojure/co/paralleluniverse/pulsar/examples/codeswap.clj)) of a `gen-server` that supports hot code swapping when `s` is redefined:

~~~ clojure
(def s (sreify Server
         (init [_])
         (terminate [_ cause])
         (handle-call [_ from id [a b]]
           (sleep 50)
           (+ a b))))

(spawn (gen-server s))
~~~

## core.async

core.async is a new [asynchronous programming library](https://github.com/clojure/core.async/) for Clojure built by Rich Hickey and other contributors. It provides something akin to fibers (though more limited than fibers) and channels, and is also available in ClojureScript. Because core.async provides a subset of Pulsar's capability, Pulsar provides an optional API that's compatible with core.async which some people may prefer.

The core.async implementation is found in the `co.paralleluniverse.pulsar.async` namespace. It defines the following vars: `chan`, `buffer`, `dropping-buffer`, `sliding-buffer`, `go`, `thread-call`, `thread`, `close!`, `take!`, `put!`, `>!`, `>!!`, `<!`, `<!!`, `alts!`, `alts!!`, `alt!`, `alt!!` and `timeout`.

These definitions are no more than thin wrappers around Pulsar functions and macros:

* `(chan)` is the same as calling `(channel)`.
* `(chan (buffer n))` or `(chan n)` are the same as `(channel n :block)` or `(channel n)`.
* `(chan (dropping-buffer n))` is the same as `(channel n :drop)`
* `(chan (sliding-buffer n))` is the same as `(channel n :displace)`
* `close!` is the same as `close!`
* `>!` and `>!!` are the same as `snd`
* `<!` and `<!!` are the same as `rcv`

The core.async API and the Pulsar API may be used interchangeably, so you can call `>!` on a channel created with `channel` or `snd` on a channel created with `chan`. They are one and the same.

`go` simply performs its body within a newly spawned fiber.

These are the differences between the Pulsar implementation of the core.async API, and the original implementation:

* Channels created with `(chan (sliding-buffer n))` are single-consumer.
* You may use the `!` and the `!!` defs interchangeably (the original implementation forces the use of the single-bang defs in go-blocks and the double-bang defs in regular threads). This means that in the Pulsar implementation all of the double-bang names (`>!!`, `<!!`, `alts!!` and `alt!!`) are actually redundant (but included for compatibility).

In addition, there are performance differences, mostly resulting from the fact that Pulsar uses fork-join pools to schedule fibers, while core.async uses regular thread pools. In short, if there is little interaction between go blocks (say, each go block writes something to a channel and completes), you can expet better performance from the original implementation. If there is a lot of interaction between go blocks (there's non-trivial message passing among them), then the Pulsar implementation will yield better performance.

## Clustering

{% capture examples %}https://github.com/{{site.github}}/tree/master/src/test/clojure/co/paralleluniverse/pulsar/examples{% endcapture %}

Pulsar is able to run on a cluster, thereby letting actors and channels communicate across machines. The Pulsar/Quasar cluster runs on top of [Galaxy](http://docs.paralleluniverse.co/galaxy/), Parallel Universe's in-memory data grid.

In this version, clustering is pretty rudimentary, but essential features should work: actors can be made discoverable on the network, messages can be passed among actors on different nodes, and an actor on a failing node will behave as expected of a dying actor with respect to exit messages sent to other, remote, *watching* it or *linked* to it.

### Enabling Clustering

First, you will need to add `quasar-galaxy` as a dependency to your project:

~~~ clojure
[co.paralleluniverse/quasar-galaxy "{{site.version}}"]
~~~

To make an actor discoverable, all you need to do is register it:

~~~ clojure
(register actor :global-actor1)
~~~

or, if the actor already has a name (set in `spawn`), simply call:

~~~ clojure
(register)
~~~

That's it. The actor is now known throughout the cluster. If you want to send a message to it, call

~~~ clojure
(! :global-actor1 [:hi-from @self])
~~~

Though this call looks up the actor in the registry every time it's called. A better way might be:

~~~ clojure
(let [a (whereis :global-actor1)]
   (! a [:hi-from @self]))
~~~

An actor doesn't have to be registered in order to be reachable on the network. Registering it simply makes it *discoverable*. If we pass a local actor in a message to a remote actor, the remote actor will be able to send messages to the local actor as well. In the simple example above, we are sending @self to :global-actor1; :global-actor1 will be able to send messages back to us.

### Example

The best way to get started is by running the distributed pingpong example.

Here's the code for [ping]({{examples}}/cluster/ping.clj) and for [pong]({{examples}}/cluster/pong.clj). You run them like this:

~~~ sh
lein with-profile cluster update-in :jvm-opts conj '"-Dgalaxy.nodeId=2"' '"-Dgalaxy.port=7052"' '"-Dgalaxy.slave_port=8052"' -- run -m co.paralleluniverse.pulsar.examples.cluster.ping
lein with-profile cluster update-in :jvm-opts conj '"-Dgalaxy.nodeId=1"' '"-Dgalaxy.port=7051"' '"-Dgalaxy.slave_port=8051"' -- run -m co.paralleluniverse.pulsar.examples.cluster.pong
~~~

### Cluster Configuration

For instructions on how to configure the Galaxy cluster, please refer to Galaxy's [getting started guide](http://docs.paralleluniverse.co/galaxy/#getting-started).

## Examples

{% capture examples %}https://github.com/{{site.github}}/tree/master/src/test/clojure/co/paralleluniverse/pulsar/examples{% endcapture %}

The Pulsar source code contains several examples:

* [A Pulsar port]({{examples}}/pingpong.clj) of the canonical [Erlang ping-pong example](http://www.erlang.org/doc/getting_started/conc_prog.html#id67006), and [one that uses registration]({{examples}}/pingpong_register.clj), as in [this Erlang example](http://www.erlang.org/doc/getting_started/conc_prog.html#id67347).
* [A simple example]({{examples}}/selective.clj), used in the user manual, of selective receive.
* [The same example]({{examples}}/selective_gen_server.clj), only using `gen-server`.
* [A Pulsar port]({{examples}}/priority.clj) of [this example](http://learnyousomeerlang.com/more-on-multiprocessing#selective-receives) from the book *[Learn You Some Erlang for great good!](http://learnyousomeerlang.com/)*
* An example of [using gloss for binary-buffer matching]({{examples}}/binary.clj).
* [A Pulsar ring benchmark]({{examples}}/ring_benchmark.clj) with actors.
* [A Pulsar ring benchmark]({{examples}}/primitive_ring_benchmark.clj) with promitive channels.
* [A Pulsar full-graph benchmark]({{examples}}/graph.clj) where all actors ping and pong with all other actors.

In addition, the [test suite](https://github.com/{{site.github}}/blob/master/src/test/clojure/co/paralleluniverse/pulsar_test.clj) contains many more small examples.
