import sbt._

object Dependencies {
  lazy val logbackClassic      = "ch.qos.logback"             %  "logback-classic"     % "1.2.3"
  lazy val twitterHPACK        = "com.twitter"                %  "hpack"               % "1.0.2"
  lazy val asyncHttpClient     = "org.asynchttpclient"        %  "async-http-client"   % "2.12.3"
  lazy val log4s               = "org.log4s"                  %% "log4s"               % "1.10.0"
  lazy val scalacheck          = "org.scalacheck"             %% "scalacheck"          % "1.15.4"
  lazy val specs2              = "org.specs2"                 %% "specs2-core"         % "4.12.1"
  lazy val specs2Mock          = "org.specs2"                 %% "specs2-mock"         % specs2.revision
  lazy val specs2Scalacheck    = "org.specs2"                 %% "specs2-scalacheck"   % specs2.revision
}
