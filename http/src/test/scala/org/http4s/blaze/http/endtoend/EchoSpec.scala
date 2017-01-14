//package org.http4s.blaze.http.endtoend
//
//import org.http4s.blaze.servertestsuite.TestScaffold
//import org.scalacheck.Properties
//import org.specs2.ScalaCheck
//import org.specs2.mutable.{BeforeAfter, Specification}
//
//import scala.concurrent.duration._
//
//class EchoSpec extends Specification with ScalaCheck {
//
////  override def before: Any = {
////    server = new LocalServer(0)(EchoService.service)
////  }
////
////  override def after: Any = {
////    server.close()
////    server = _
////  }
////
////  "this is a specific property" >> prop { (a: Int, b: Int) =>
////    (a + b) must_== (b + a)
////  }.set(minTestsOk = 200, workers = 3) // use "display" instead of "set" for additional console printing
//
//  "run some echos"! {
//    LocalServer.withLocalServer(EchoService.service) { address =>
//      val scaffold = new TestScaffold("localhost", address.getPort, 10.seconds)
//      scaffold.withArbitraryRequest()
//    }
//
//  }
//}
