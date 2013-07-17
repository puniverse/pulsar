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

~~~ clojure
(join 500 :ms fiber)
~~~

### Bindings

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

### Compatibility with Clojure Concurrency Constructs

Code running in fibers may make free use of Clojure atoms and agents. 

Spawning or dereferencing a future created with `clojure.core/future` is ok, but there's a better alternative: you can turn a spawned fiber into a future with `fiber->future` and can then dereference or call regular future functions on the returned value, like `realized?` (In fact, you don't even have to call `fiber->future`; fibers already implement the `Future` interface and can be treated as futures directly, but this may change in the future, so, until the API is fully settled, we recommend using `fiber->future`).

Running a `dosync` block inside a fiber is discouraged as it uses locks internally, but your mileage may vary.

Promises are supported and encouraged, but you should not make use of `clojure.core/promise` to create a promise that's to be dereferenced in a fiber. Pulsar provides a different -- yet completely compatible -- form of promises, as you'll see soon.

### Strands

Before we continue, one more bit of nomenclature: a single flow of execution in Quasar/Pulsar is called a *strand*. To put it more simply, a strand is either a normal JVM thread, or a fiber.

The strand abstraction helps you write code that works whether it runs in a fiber or not. For example, `(Strand/currentStrand)` returns the current fiber, if called from a fiber, or the current thread, if not. `(Strand/sleep millis)` suspends the current strand for a given number of milliseconds whether it's a fiber or a normal thread. Also, `join` works for both fibers and threads (although for threads `join` will always return `nil`).

## Promises, Promises

Promises, also known as dataflow variables, are an especially effective, and simple, concurrency primitive.

A promise is a value that may only be set once, but read multiple times. If the promise is read before the value is set, the reading strand will block until the promise has been set, and then return its value.

Promises are defined in `clojure.core`, but `...pulsar.core` provides its own, fully compatible version.

A promise is created simply like this:

    (promise)

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

## Channels {#channels}

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

### Sending and receiving messages

Sending a message to a channel is simple:

    (snd channel message)

`message` can be any object, but not `nil`. Receiving a message from a channel is equaly easy:

    (rcv channel)

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

### Closing the channel

After calling 

~~~ clojure
(close! channel)
~~~

any future messages sent to the channel will be ignored. Any messages already in the channel will be received. Once the last message has been received, another call to `rcv` will return `nil`. 

### Channel Selection – `sel` and `select`

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

### Channel Groups

It is common for a function to always wait to receive from the same set of channels. An alternative to `sel` can be to create a `rcv-group`, on which you can call `rcv` as if it were a simple channel:

~~~ clojure
(let [group (rcv-group ch1 ch2 ch3)]
   (rcv group))
~~~

You can also use a timeout when receiving from a channel group.

### Topics

Topics are, in a sense, the opposite of rcv-groups. A topic is a send-port (a channel you can send to but not receive from), that broadcasts any message written to it to a number of *subscriber* channels.

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

### Ticker Channels

A channel created with the `:displace` policy is called a *ticker channel* because it provides guarantees similar to that of a digital stock-ticker: you can start watching at any time, the messages you read are always read in order, but because of the limited screen size, if you look away or read to slowly you may miss some messages.

The ticker channel is useful when a program component continually broadcasts some information. The size channel's circular buffer, its "screen" if you like, gives the subscribers some leeway if they occasionally fall behind reading.

As mentioned earlier, a ticker channel is single-consumer, i.e. only one strand is allowed to consume messages from the channel. On the other hand, it is possible, and useful, to create several views of the channel, each used by a different consumer strand. A view is created thus:

~~~ clojure
(ticker-consumer ch)
~~~

`ch` must be a channel of bounded capacity with the `:displace` policy. `ticker-consumer` returns a *receive port* (a channel that can only receive messages, not send them) that can be used to receive messages from `ch`. Each ticker-consumer will yield monotonic messages, namely no message will be received more than once, and the messages will be received in the order they're sent, but if the consumer is too slow, messages could be lost. 

Each consumer strand will use its own `ticker-consumer`, and each can consume messages at its own pace, and each `ticker-consumer` port will return the same messages (messages consumed from one will not be removed from the other views), subject possibly to different messages being missed by different consumers depending on their pace.


### Primitive channels

It is also possible to create channels that carry messages of primitive JVM types. The analogous primitive channel functions to `channel`, `snd` and `rcv`, are, respectively: 

* `int` channels: `int-channel`, `snd-int`, `rcv-int`
* `long` channels: `long-channel`, `snd-long`, `rcv-long`
* `float` channels: `float-channel`, `snd-float`, `rcv-float`
* `double` channels: `double-channel`, `snd-double`, `rcv-double`

Because they don't require boxing (for this reason `snd-xxx` and `rcv-xxx` are actually macros), primitive channels can provide better performance than regular channels. Primitive channels, however, are single-consumer, namely, only a single strand may read messages from any given channel.

Calling `rcv-xxx` on a closed channel will throw an exception.

### Channel lazy-seqs

{:.centered .alert .alert-warning}
**Note**: Channel lazy-seqs are an experimental feature.

Messages received through channels can be manipulted with Clojure's sequence manipulation functions by transforming a channel into a `lazy-seq`. Unfortunately, Clojure implements `lazy-seq`s with the `clojure.lang.LazySeq` class, which *almost* allows a `lazy-seq` implementation by a channel - but not quite (a simple reordering of a couple of Java lines in the `LazySeq` class would have made it compatible with channels). The `lazy-seq` function in the `co.paralleluniverse.pulsar.lazyseq` namespace therefore creates a `lazy-seq` that works just like Clojure's, but uses a slightly different underlying Java implementation. This is unfortunate, because it required a re-implementation of many sequence manipulation functions.

Nevertheless, it's still possible to work with channels as you would with lazy-seqs, you only need to mind calling the functions in the right namespace.

To create a lazy-seq from a channel, simply call:

    (channel->lazy-seq ch)

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

