import sbt._

object Dependencies {
  lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.2.13"
  lazy val twitterHPACK = "com.twitter" % "hpack" % "1.0.2"
  lazy val asyncHttpClient = "org.asynchttpclient" % "async-http-client" % "3.0.4"
  lazy val log4s = "org.log4s" %% "log4s" % "1.10.0"
  lazy val munit = "org.scalameta" %% "munit" % "1.0.0-M6"
  lazy val scalacheckMunit = "org.scalameta" %% "munit-scalacheck" % munit.revision
  lazy val kindProjector = "org.typelevel" % "kind-projector" % "0.13.4"
}
