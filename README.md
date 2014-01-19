Blaze
=====

Blaze is a library written in Scala for building asynchronous pipelines, with a focus on network IO. 

#### Blazes goals are
- Speed first
- Asynchronous using scala 2.10 Futures as the underpinning
- Java NIO backend for fast/asynchronous network and file I/O
- Offer a framework for rapid development of fast, asynchronous pipelines

#### What Blaze is not
- Purely functional. Functional is nice, but often conflicts with goal one above
- Stable. Blaze is currently not considered stable so don't expect Blaze to be bug free or the interface to remain unchanged.

## Asynchronous Network I/O
Blaze was built with the Java 7 NIO2 features in mind. While many NIO frameworks have been developed with NIO1 underpinnings, NIO2 offers a host of new features which are offered in a unified way.

A TCP Socket Server skeleton is already implemented. It is basic, but simple is good for most projects.

## How to Use Blaze
For a quick start, look at the examples for some simple examples of HTTP, WebSocket, and a simple EchoServer. 

#### Important Blaze Behaviors
* By default, read and write requests should be serialized either manually, or with a SerializerStage.
* Blaze tries to minimize copying and garbage. ByteBuffers are one of the biggest culprits, so it is, in general, not safe to hold a returned ByteBuffer and request more data.

#### Dataflow

The general idea is to wait for some event, such as a socket connection which could benefit from asynchronous transformations, and build a pipeline with which to transform data from that event.

Data flow is typically controlled by the tip of the pipeline which requests data to be delivered in the form of a Future, and writes data with the returned Future signaling success/failure. Blaze doesn't make any assumptions that each stage is thread safe, so as noted above, either manually ensure a write is not performed before the previous write has completed and the same for reads, or use a SerializerStage or Serializer mixin to automatically manage reads and writes for you.

## Development
Blaze is under heavy development and at this stage, everything is candidate for change. The timeline for development is undefined as there is only one active developer on the project. Contributions and comments are helpful and appreciated.

### Acknowledgments
The structure of this project is heavily influenced by the Netty Project, and outstanding NIO framework for Java. Those looking for a more stable Java NIO framework are encouraged to take a look at www.netty.io

### Contributors
Bryce Anderson <bryce.anderson22@gmail.com>

### Licence
Blaze is licensed under the Apache 2.0 license. See the license file in the root project directory for more information.
