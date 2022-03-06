import sbt._

object Dependencies {
  lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.2.11"
  lazy val twitterHPACK = "com.twitter" % "hpack" % "1.0.2"
  lazy val asyncHttpClient = "org.asynchttpclient" % "async-http-client" % "2.12.3"
  lazy val log4s = "org.log4s" %% "log4s" % "1.10.0"
  lazy val munit = "org.scalameta" %% "munit" % "0.7.27"
  lazy val scalacheckMunit = "org.scalameta" %% "munit-scalacheck" % munit.revision
  lazy val kindProjector = "org.typelevel" % "kind-projector" % "0.13.2"
}
