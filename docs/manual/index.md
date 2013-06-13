---
layout: default
title: User Manual
weight: 1
---

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

Spawning or dereferncing a future creted with `clojure.core/future` is ok, but there's a better alternative: you can turn a spawned fiber into a future with `fiber->future` and can then dereference or call regular future functions on the returned value, like `realized?` (In fact, you don't even have to call `fiber->future`; fibers already implement the `Future` interface and can be treated as futures directly, but this may change in the future, so, until the API is fully settled, we recommend using `fiber->future`).

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

### Primitive channels

It is also possible to create channels that carry messages of primitive JVM types. The analogous primitive channel functions to `channel`, `snd` and `rcv`, are, respectively: 

* `int` channels: `int-channel`, `snd-int`, `rcv-int`
* `long` channels: `long-channel`, `snd-long`, `rcv-long`
* `float` channels: `float-channel`, `snd-float`, `rcv-float`
* `double` channels: `double-channel`, `snd-double`, `rcv-double`

Because they don't require boxing (for this reason `snd-xxx` and `rcv-xxx` are actually macros), primitive channels can provide better performance than regular channels.

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

## Actors

To use the terms we've learned so far, an *actor* is a strand that owns a single channel with some added lifecyce management and error handling. But this reductionist view of actors does them little justice. Actors are fundamental building blocks that are combined to build a fault-tolerant application. If you are familiar with Erlang, Pulsar actors are just like Erlang processes.

An actor is a self-contained execution unit with well-defined inputs and outputs. Actors communicate with other actors (as well as regular program threads and fibers) by passing messages.

{:.alert .alert-info}
**Note**: Actors may write to and read from channels other than their own mailbox. In fact, actors can do whatever regular fibers can.

### Spawning actors

Actors can run in any strand – fiber or thread - but for now, Pulsar only supports actors running in fibers (Quasar, the Java library that Pulsar wraps, allows running actors in regular threads).

An actor is basically a function that -- if the actor is to do anything interesting -- receives messages from the mailbox.
To create and start an actor of a function `f` that takes arguments `arg1` and `arg2`, run

    (spawn f arg1 arg2)

This will create a new actor, and start running it in a new fiber.

`spawn` automatically marks `f` as suspendable, so there's no need to do so explicitly.

`spawn` takes optional keyword arguments:

* `:name` - The actor's name (that's also given to the fiber running the actor).
* `:mailbox-size` - The number of messages that can wait in the mailbox, or -1 (the default) for an unbounded mailbox.
* `:lifecycle-handle` - A function that will be called to handle special messages sent to the actor. If set to `nil` (the default), the default handler is used, which is what you want in all circumstances, except for some actors that are meant to do some special tricks.
* `:fj-pool` - The `ForkJoinPool` in which the fiber will run.
  If `:fj-pool` is not specified, then the pool used will be either 1) the pool of the fiber calling `spawn-fiber`, or, if `spawn-fiber` is not called from within a fiber, a default pool.
* `:stack-size` - The initial fiber data stack size.

Of all the optional arguments, you'll usually only use `:name` and `:mailbox-size`. As mentioned, by default the mailbox is unbounded. Bounded mailboxes provide better performance and should be considered for actors that are expected to handle messages at a very high rate. However, a bounded mailbox that overflows, will inject an exception into the *receiving* actor, which will, in most circumstances, cause it to terminate.

An actor can be `join`ed, just like a fiber.

{:.alert .alert-info}
**Note**: Just like fibers, spawning an actor is a very cheap operation in both computation and memory. Do not fear creating many (thousands, tens-of-thousands or even hundereds-of-thousands) actors.

### Sending and Receiving Messages

An actor's mailbox is a channel, that can be obtained with the `mailbox-of` function. You can therefore send a message to an actor like so:

    (snd (mailbox-of actor) msg)

But there's an easier way. Actors implement the `SendChannel` interface, and so, are treated like a channel by the `snd` function. So we can simple call:

    (snd actor msg)

While the above is a perfectly valid way of sending a message to an actor, this is not how it's normally done. Instead of `snd` we normally use the `!` (bang) function to send a message to an actor, like so:

    (! actor msg)

The bang operator has a slightly different semantic than `snd`. While `snd` will always place the message in the mailbox, `!` will only do it if the actor is alive. It will not place a message in the mailbox if there is no one to receive it on the other end (and never will be, as mailboxes, like all channels, cannot change ownership).

In many circumstances, an actor sends a message to another actor, and expects a reply. In those circumstances, using `!!` instead of `!` might offer reduced latency (but with the same semantics; both `!` and `!!` always return `nil`)

The value `@self`, when evaluated in an actor, returns the actor. So, as you may guess, an actor can receive messages with:

    (rcv (mailbox-of @self))

`@mailbox` returns the actor's own mailbox channel, so the above may be written as:

    (rcv @mailbox)

... and because actors also implement the `ReceiveChannel` interface required by `rcv`, the following will also work:

    (rcv @self)

But, again, while an actor can be treated as a fiber with a channel, it has some extra features that give it a super-extra punch. Actors normally receive messages with the `receive` function, like so:

    (receive)

`receive` has some features that make it very suitable for handling messages in actors. Its most visible feature is pattern matching. When an actor receives a message, it usually takes different action based on the type and content of the message. Making the decision with pattern matching is easy and elegant:

~~~ clj
(let [actor (spawn
               #(receive
                   :abc "yes!"
                   [:why? answer] answer
                   :else "oy"))]
     (! actor [:why? "because!"])
     (join actor)) ; => "because!"
~~~

As we can see in the example, `receive` not only picks the action based on the message, but also destructures the message and binds free variable, in our example – the `answer` variable. `receive` uses the [core.match](https://github.com/clojure/core.match) library for pattern matching, and you can consult [its documentation](https://github.com/clojure/core.match/wiki/Overview) to learn exactly how matching works.

Sometimes, we would like to assign the whole message to a variable. We do it by creating a binding clause in `receive`:

~~~ clj
(receive [m]
   [:foo val] (println "got foo:" val)
   :else      (println "got" m))
~~~

We can also match not on the raw message as its been received, but transform it first, and then match on the transformed value, like so, assuming `transform` is a function that takes a single argument (the message):

~~~ clj
(receive [m transform]
   [:foo val] (println "got foo:" val)
   :else      (println "got" m))
~~~

Now `m` – and the value we're matching – is the the transformed value.

`receive` also deals with timeouts. Say we want to do something if a message has not been received within 30 milliseconds (all `receive` timeouts are specified in milliseconds):

~~~ clj
(receive [m transform]
   [:foo val] (println "got foo:" val)
   :else      (println "got" m)
   :after 30  (println "nothing..."))
~~~

{:.alert .alert-warn}
**Note**: The `:after` clause in `receive` *must* be last.

Before we move on, it's time for a short example. In this example, we will define an actor, `adder`, that receives an `:add` message with two numbers, and reply to the sender with the sum of those two numbers. In order to reply to the sender, we need to know who the sender is. So the sender will add a reference to itself in the message. In this request-reply pattern, it is also good practice to attach a random unique tag to the request, because messages are asynchronous, and it is possible that the adder will not respond to the requests in the order they were received, and the requester might want to send two requests before waiting for a response, so a tag is a good way to match replies with their respective requests. We can generate a random tag with the `maketag` function.

Here's the adder actor:

~~~ clj
(defsusfn adder []
  (loop []
    (receive
     [from tag [:add a b]] (! from tag [:sum (+ a b)]))
    (recur)))
~~~

And this is how we'll use it from within another actor:

~~~ clj
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

~~~ clj
(let [m1 (rcv 30 :ms)]
   (if m1
      (let [m (transform m1)]
         (match (transform (rcv 30 :ms))
             [:foo val]  (println "got foo:" val)
   		     :else      (println "got" m)))
   	   (println "nothing...")))
~~~

Pretty syntax is not the main goal of the `receive` function. The reason `receive` is much more powerful than `rcv`, is mostly because of a feature we will now introduce.

### Selective Receive

An actor is a state machine. It usually encompasses some *state* and the messages it receives trigger *state transitions*. But because the actor has no control over which messages it receives and when (which can be a result of either other actors' behavior, or even the way the OS schedules threads), an actor would be required to process any message and any state, and build a full *state transition matrix*, namely how to transition whenever *any* messages is received at *any* state.

This can not only lead to code explosion; it can lead to bugs. The key to managing a complex state machine is by not handling messages in the order they arrive, but in the order we wish to process them. 

.....

There are several actor systems that do not support selective receive, but Erlang does, and so does Pulsar. [The talk *Death by Accidental Complexity*](http://www.infoq.com/presentations/Death-by-Accidental-Complexity), by Ulf Wiger, shows how using selective receive avoids implementing a full, complicated and error-prone transition matrix. [In a different talk](http://www.infoq.com/presentations/1000-Year-old-Design-Patterns), Wiger compared non-selective (FIFO) receive to a tetris game where you must fit each piece into the puzzle as it comes, while selective receive turns the problem into a jigsaw puzzle, where you can look for a piece that you know will fit.

{:.alert .alert-warn}
**A word of caution**: Using selective receive in your code may lead to deadlocks (because you're essentially saying, I'm going to wait here until a specific message arrives). This can be easily avoided by always specifying a timeout (with the `:after millis` clause) when doing a selective receive. Selective receive is a powerful tool that can greatly help writing readable, maintainable message-handling code, but don't over-use it.

### Actor State


### State Machines with `strampoline`



### Error Handling


### Actor registration




## Behaviors

### gen-server

### Supervisors


## Dataflow


