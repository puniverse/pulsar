---
layout: default
title: Pulsar Core
weight: 1
---

{% capture examples %}https://github.com/{{site.github}}/tree/master/src/test/clojure/co/paralleluniverse/pulsar/examples{% endcapture %}

## Quasar and Pulsar

Pulsar is a Clojure API to [Quasar]. Many of the concepts explained below are actually implemented in Quasar.

[Quasar]: https://github.com/puniverse/quasar


## Fibers {#fibers}

Fibers are lightweight threads. They provide functionality similar to threads, and a similar API, but they're not managed by the OS. They are lightweight (an idle fiber occupies ~400 bytes of RAM), and you can have millions of them in an application. If you are familiar with Go, fibers are like goroutines. Fibers in Pulsar (well, Quasar, actually) are scheduled by one or more [ForkJoinPool](http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/ForkJoinPool.html)s. 

One significant difference between Fibers and Threads is that Fibers are not preempted; i.e. a fiber is (permanently or temporarily) unscheduled by the scheduler only if it terminates, or if it calls one of a few specific Java methods that cause the fiber to become suspended. A function that calls a suspending operation is called a *suspendable* function, and a function that calls another suspendable function is itself suspendable. 

Suspendable functions require special bytecode instrumentation (performed by an instrumentation agent), so they must be explicitly designated as such.
The function `suspendable!` marks a given function as a suspendable function (this operation cannot be undone). The `defsusfn` macro, with the same syntax as `defn` defines a suspendable function.

{:.alert .alert-info}
**Note**: All functions (i.e. `fn`s) passed to any of the Pulsar API functions and macros are automatically made suspendable, so in most simple cases you will never need to use `susfn`, `defsusfn` or `suspendable!`.

### Spawning Fibers

To create a fiber of a function `f` that takes arguments `arg1` and `arg2`, run

    (spawn-fiber f arg1 arg2)

`spawn-fiber` automatically marks `f` as suspendable, so there's no need to do so explicitly.

`spawn-fiber` takes optional keyword arguments:

* `:name` - The fiber's name.
* `:fj-pool` - The `ForkJoinPool` in which the fiber will run.
  If `:fj-pool` is not specified, then the pool used will be either 1) the pool of the fiber calling `spawn-fiber`, or, if `spawn-fiber` is not called from within a fiber, a default pool.
* `:stack-size` - The initial fiber data stack size.

The fiber will terminate when `f` completes execution. 

{:.alert .alert-info}
**Note**: Spawning a fiber is a very cheap operation in both computation and memory. Do not fear creating many (thousands, tens-of-thousands or even hundereds-of-thousands) fibers.

### Joining Fibers

To wait for the fiber's termination, use

    (join fiber)

If `f` returns a value, `join` will return that value. If `f` throws an exception, `join` will throw that exception.

You can also wait for a fiber's termination for a given duration. The following will wait for half a second for the fiber to terminate:

    (join 500 java.util.concurrent.TimeUnit/MILLISECONDS fiber)

The following will have the same effect:

~~~ clj
(join 500 :ms fiber)
~~~

### Bindings

Fibers behave well with Clojure `bindings`. A newly spawned fiber inherits the bindings in effect at the time of spawning,
and bindings decleared in a fiber last throughout the fiber's lifetime. This is demonstrated in the following tests taken
from the Pulsar test suite:

~~~ clj
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

### Compatibility with Clojure Concurrency Constructs

Code running in fibers may make free use of Clojure atoms and agents. 

Spawning or dereferencing a future created with `clojure.core/future` is ok, but there's a better alternative: you can turn a spawned fiber into a future with `fiber->future` and can then dereference or call regular future functions on the returned value, like `realized?` (In fact, you don't even have to call `fiber->future`; fibers already implement the `Future` interface and can be treated as futures directly, but this may change in the future, so, until the API is fully settled, we recommend using `fiber->future`).

Running a `dosync` block inside a fiber is discouraged as it uses locks internally, but your mileage may vary.

Promises are supported and encouraged, but you should not make use of `clojure.core/promise` to create a promise that's to be dereferenced in a fiber. Pulsar provides a different -- yet completely compatible -- form of promises, as you'll immediately see.

### Promises, Promises

Promises, also known as dataflow variables, are an especially effective, and simple, concurrency primitive.

A promise is a value that may only be set once, but read multiple times. If the promise is read before the value is set, the reading strand will block until the promise has been set, and then return its value.

Promises are defined in `clojure.core`, but `...pulsar.core` provides its own, fully compatible version.

A promise is created simply like this:

    (promise)

And is set with `deliver`. It can be read by dereferencing it with `@`, and you can test whether it's been set with `realized?` (other than the `promise` function itself, all other functions, like `deliver` and `realized` are those defined in `clojure.core`)

The `promise` function defined in Pulsar creates a promise, that, when dereferenced within a fiber, simply blocks the fiber and not the entire OS thread it's running in.

Here's an example of using promises from the tests:

~~~ clj
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

~~~ clj
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

### Strands

Before we continue, one more bit of nomenclature: a single flow of execution in Quasar/Pulsar is called a *strand*. To put it more simply, a strand is either a normal JVM thread, or a fiber.

The strand abstraction helps you write code that works whether it runs in a fiber or not. For example, `(Strand/currentStrand)` returns the current fiber, if called from a fiber, or the current thread, if not. `(Strand/sleep millis)` suspends the current strand for a given number of milliseconds whether it's a fiber or a normal thread. Also, `join` works for both fibers and threads (although for threads `join` will always return `nil`).

## Channels {#channels}

Channels are queues used to pass messages between strands (remember, strands are a general name for threads and fibers). If you are familiar with Go, Pulsar channels are like Go channels. The call

    (channel)

creates and returns a new channel. A channel has a capacity, i.e. the number of messages it can hold before they are consumed. By default, the `channel` function creates an unbounded channel. To create a channel with a limited capacity, call

    (channel size)

with `size` being a positive integer. Bounded channels are generally faster than unbounded channels. Attempting to send a message to a bounded channel that has filled up will throw an exception.

Many strands can send messages to the channel, but only one may receive messages from the channel. The strand that receives messages from the channel is the channel's *owner*. The owner can be set either explicitely by calling:

    (attach! channel owner)

where `owner` is either a fiber or a thread, but usually this is not necessary. The first strand that tries to receive a message from a channel becomes its owner. Attempting to receive from a channel on a strand that is not the channel's owner results in an exception.

### Sending and receiving messages

Sending a message to a channel is simple:

    (snd channel message)

`message` can be any object, but not `nil`. Receiving a message from a channel is equaly easy:

    (rcv channel)

The `rcv` function returns the first message in the channel (the one that has waited there the longest), if there is one. If the channel is empty, the function will block until a message is sent to the channel, and will then return it.

{:.alert .alert-info}
**Note**: `rcv` is a suspendable function, so any function calling it must also be decalred suspendable. But remember, the function passed to `spawn-fiber` is automatically made suspendable.

It is also possible to limit the amount of time `rcv` will wait for a message:

~~~ clj
(rcv channel 10 java.util.concurrent.TimeUnit/MILLISECONDS)
~~~

or, equivalently:

~~~ clj
(rcv channel 10 :ms)
~~~

These calls will wait for a message for 10 milliseconds before giving up and returning `nil`.

### Closing the channel

After calling 

~~~ clj
(close channel)
~~~

any future messages sent to the channel will be ignored. Any messages already in the channel will be received. Once the last message has been received, another call to `rcv` will return `nil`. 

### Primitive channels

It is also possible to create channels that carry messages of primitive JVM types. The analogous primitive channel functions to `channel`, `snd` and `rcv`, are, respectively: 

* `int` channels: `int-channel`, `snd-int`, `rcv-int`
* `long` channels: `long-channel`, `snd-long`, `rcv-long`
* `float` channels: `float-channel`, `snd-float`, `rcv-float`
* `double` channels: `double-channel`, `snd-double`, `rcv-double`

Because they don't require boxing (for this reason `snd-xxx` and `rcv-xxx` are actually macros), primitive channels can provide better performance than regular channels.

Calling `rcv-xxx` on a closed channel will throw an exception.

### Channel Groups

A strand may also wait for a message to arrive on several channels at once by creating a `channel-group`:

{:.prettyprint .lang-clj}
    (rcv (channel-group ch1 ch2 ch3))

You can also use a timeout when receiving from a channel group.


### Channel lazy-seqs

{:.centered .alert .alert-warning}
**Note**: Channel lazy-seqs are an experimental feature.

Messages received through channels can be manipulted with Clojure's sequence manipulation functions by transforming a channel into a `lazy-seq`. Unfortunately, Clojure implements `lazy-seq`s with the `clojure.lang.LazySeq` class, which *almost* allows a `lazy-seq` implementation by a channel - but not quite (a simple reordering of a couple of Java lines in the `LazySeq` class would have made it compatible with channels). The `lazy-seq` function in the `co.paralleluniverse.pulsar.lazyseq` namespace therefore creates a `lazy-seq` that works just like Clojure's, but uses a slightly different underlying Java implementation. This is unfortunate, because it required a re-implementation of many sequence manipulation functions.

Nevertheless, it's still possible to work with channels as you would with lazy-seqs, you only need to mind calling the functions in the right namespace.

To create a lazy-seq from a channel, simply call:

    (channel->lazy-seq ch)

Then, manipulating messages with sequence functions is easy. Here are some examples from the tests:

~~~ clj
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
                 (Thread/sleep 20)null
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

