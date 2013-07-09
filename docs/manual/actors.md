---
layout: default
title: Pulsar's Actor System
weight: 2
---


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
* `:overflow-policy` - What to do if a bounded mailbox overflows. Can be either `:kill`, in which case an exception will be thrown into the receivnig actor, or `:backpressure`, in which case the sender will block until there's room in the mailbox.
* `:lifecycle-handle` - A function that will be called to handle special messages sent to the actor. If set to `nil` (the default), the default handler is used, which is what you want in all circumstances, except for some actors that are meant to do some special tricks.
* `:fj-pool` - The `ForkJoinPool` in which the fiber will run.
  If `:fj-pool` is not specified, then the pool used will be either 1) the pool of the fiber calling `spawn-fiber`, or, if `spawn-fiber` is not called from within a fiber, a default pool.
* `:stack-size` - The initial fiber data stack size.

Of all the optional arguments, you'll usually only use `:name` and `:mailbox-size`+`:overflow-policy`. As mentioned, by default the mailbox is unbounded. Bounded mailboxes provide better performance and should be considered for actors that are expected to handle messages at a very high rate.

An actor can be `join`ed, just like a fiber.

{:.alert .alert-info}
**Note**: Just like fibers, spawning an actor is a very cheap operation in both computation and memory. Do not fear creating many (thousands, tens-of-thousands or even hundereds-of-thousands) actors.

## Sending and Receiving Messages

An actor's mailbox is a channel, that can be obtained with the `mailbox-of` function. You can therefore send a message to an actor like so:

    (snd (mailbox-of actor) msg)

But there's an easier way. Actors implement the `SendPort` interface, and so, are treated like a channel by the `snd` function. So we can simple call:

    (snd actor msg)

While the above is a perfectly valid way of sending a message to an actor, this is not how it's normally done. Instead of `snd` we normally use the `!` (bang) function to send a message to an actor, like so:

    (! actor msg)

The bang operator has a slightly different semantic than `snd`. While `snd` will always place the message in the mailbox, `!` will only do it if the actor is alive. It will not place a message in the mailbox if there is no one to receive it on the other end (and never will be, as mailboxes, like all channels, cannot change ownership).

In many circumstances, an actor sends a message to another actor, and expects a reply. In those circumstances, using `!!` instead of `!` might offer reduced latency (but with the same semantics; both `!` and `!!` always return `nil`)

The value `@self`, when evaluated in an actor, returns the actor. So, as you may guess, an actor can receive messages with:

    (rcv (mailbox-of @self))

`@mailbox` returns the actor's own mailbox channel, so the above may be written as:

    (rcv @mailbox)

... and because actors also implement the `ReceivePort` interface required by `rcv`, the following will also work:

    (rcv @self)

But, again, while an actor can be treated as a fiber with a channel, it has some extra features that give it a super-extra punch. Actors normally receive messages with the `receive` function, like so:

    (receive)

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
(defsusfn adder []
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

## Actors vs. Channels

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

## Selective Receive

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
(defsusfn adder []
  (loop []
    (receive
      [from tag [:add a b]] (! from tag [:sum (+ a b)]))
    (recur)))

(defsusfn computer [adder]
  (loop []
    (receive [m]
             [from tag [:compute a b c d]] (let [tag1 (maketag)]
                                             (! adder [@self tag1 [:add (* a b) (* c d)]])
                                             (receive
                                               [tag1 [:sum sum]]  (! from tag [:result sum])
                                               :after 10          (! from tag [:error "timeout!"])))
             :else (println "Unknown message: " m))
    (recur)))

(defsusfn curious [nums computer]
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

## Actor State

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

## State Machines with strampoline

As we've seen, the `receive` form defines which messages the actor is willing to accept and process. You can nest `receive` statements, or place them in other functions that the actor calls (in which case the must be defined with `defsusfn`). It is often useful to treat the actor as a state machine, going from one state to another, executing a different `receive` at each state (to define the acceptable transitions from the state). To change state, all we would have to do is call a different function, each with its own receive, but here we face a technical limitation of Clojure. As Clojure (due to JVM limitations) does not perform true tail-call optimization, every state transition (i.e. every function call), would add a frame to the stack, eventually throwing a stack overflow. Clojure solves it with the `clojure.core/trampoline` function. It takes a function and calls it. When the function returns, if the returned value is a function, `trampoline` calls it.

Pulsar comes with a version of `trampoline` for suspendable functions called `strampoline` (with the exact same API as `trampoline`).

Consider this example:

~~~ clojure
(let [state2 (susfn []
                    (receive
                      :bar :foobar))
      state1 (susfn []
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
(let [state2 (susfn []
                    (receive
                      :bar :foobar))
      state1 (susfn []
                    (receive
                      :foo state2))
      actor (spawn (fn []
                     (strampoline state1)))]
  (! actor :bar)
  (Thread/sleep 50) ; or (Strand/sleep 50)
  (! actor :foo)
  (join actor)) ; => :foobar
~~~

## Error Handling

The actor model does not only make concurrency easy; it also helps build fault-tolerant systems by compartmentalizing failure. Each actor is it's own execution context - if it encounters an exception, only the actor is directly affected (like a thread, only actors are lightweight). Unlike regular functions/objects, where an exception has to be caught and handled immediately on the callstack, with actors we can completely separate code execution from error handling.

In fact, when using actors, it is often best to to follow the [philosophy laid out by Joe Armstrong](http://www.erlang.org/download/armstrong_thesis_2003.pdf), Erlang's chief designer, of "let it crash". The idea is not to try and catch exceptions inside an actor, because attempting to catch and handle all exceptions is futile. Instead, we just let the actor crash, monitor its death elsewhere, and then take some action.

The principle of actor error handling is that an actor can be asked to be notified of another actor's death. This is done through *linking* and *watching*. 

### Linking actors

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

### Watching actors

A more robust way than linking of being notified of actor death is with a *watch* (called *monitor* in Erlang; this is one of the very few occasions we have abandoned the Erlang function names):

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

## Actor registration



## Behaviors

### gen-server

### gen-event

## Supervisors



