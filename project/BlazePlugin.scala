package org.http4s.sbt

import sbt._
import sbt.Keys._

import sbtrelease._
import sbtrelease.ReleasePlugin.autoImport._

object BlazePlugin extends AutoPlugin {

  object autoImport

  override def trigger = allRequirements

  override def requires = Http4sOrgPlugin && ReleasePlugin

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    // Override rig's default of the Travis build number being the bugfix number
    releaseVersion := { ver =>
      Version(ver).map(_.withoutQualifier.string).getOrElse(versionFormatError(ver))
    },
    scalaVersion := (sys.env.get("TRAVIS_SCALA_VERSION") orElse sys.env.get("SCALA_VERSION") getOrElse "2.12.11"),

    fork in run := true
  )

  lazy val logbackClassic      = "ch.qos.logback"             %  "logback-classic"     % "1.2.3"
  lazy val twitterHPACK        = "com.twitter"                %  "hpack"               % "1.0.2"
  lazy val asyncHttpClient     = "org.asynchttpclient"        %  "async-http-client"   % "2.12.2"
  lazy val log4s               = "org.log4s"                  %% "log4s"               % "1.10.0-M3"
  lazy val scalacheck          = "org.scalacheck"             %% "scalacheck"          % "1.15.2"
  lazy val specs2              = "org.specs2"                 %% "specs2-core"         % "4.10.5"
  lazy val specs2Mock          = "org.specs2"                 %% "specs2-mock"         % specs2.revision
  lazy val specs2Scalacheck    = "org.specs2"                 %% "specs2-scalacheck"   % specs2.revision
  // Needed for Http2 support until implemented in the JDK
  lazy val alpn_api            = "org.eclipse.jetty.alpn"     % "alpn-api"             % "1.1.3.v20160715"
  // Note that the alpn_boot version is JVM version specific. Check the docs if getting weird errors.
  // Also note that only java8 and above has the require cipher suite for http2.
  lazy val alpn_boot           = "org.eclipse.jetty"          % "jetty-alpn-openjdk8-client" % "9.4.35.v20201120"

}
