blaze
=====

blaze is a library written in Scala for building asynchronous pipelines, with a focus on network IO. Its main goal is
to service the core http4s project, but may find application elsewhere.

#### blazes goals are
- Speed is the first priority
- Asynchronous using scala 2.10 Futures as the underpinning
- Java NIO backend for fast/asynchronous network and file I/O
- Offer a framework for rapid development of fast, asynchronous pipelines using scala

#### What blaze is not
- Purely functional. Functional is preferred except when in conflict with the first goal
- Completely stable. blaze remains a work in progress and cannot be guaranteed to be bug free. Bug reports and patches
  are always appreciated!

## Asynchronous network I/O
blaze was built with the Java 7 NIO2 features in mind. NIO2 offers a host of new features which are offered 
in a unified way. blaze offers TCP socket server support for both NIO1 and NIO2, depending on your preference. 

## How to use blaze
For a quick start, look at the examples for some simple examples of HTTP, WebSocket, and a simple EchoServer. 

#### Important blaze behaviors
* By default, read and write requests should be serialized either manually, or with a SerializerStage.

#### Dataflow

The general idea is to wait for some event, such as a socket connection which could benefit from asynchronous
transformations, and build a pipeline with which to transform data from that event.

Data flow is typically controlled by the tip of the pipeline which requests data to be delivered in the
form of a Future, and writes data with the returned Future signaling success/failure. blaze doesn't make any
assumptions that each stage is thread safe, so as noted above, either manually ensure a write is not performed before
the previous write has completed and the same for reads, or use a SerializerStage or Serializer mixin to automatically
manage reads and writes for you.

## Development
blaze is under heavy development and at this stage, everything is candidate for change. Contributions 
and comments are both helpful and very appreciated.

### Acknowledgments
The structure of this project is heavily influenced by the Netty Project, and outstanding NIO framework for Java.
Those looking for a more mature and universal Java NIO framework are encouraged to take a look at
[netty](http://netty.io/)

### Contributors
[The Contributors](https://github.com/http4s/blaze/graphs/contributors?from=2013-01-01&type=c), as calculated by GitHub.
Several concepts are inspired by the [Jetty](http://http://www.eclipse.org/jetty/) and [Netty](http://netty.io/)
projects.

### Licence
Blaze is licensed under the Apache 2 license. See the LICENSE file in the root project directory for more information.
