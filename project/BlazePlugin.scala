package org.http4s.build

import sbt._
import Keys._

import com.typesafe.tools.mima.plugin.MimaPlugin, MimaPlugin.autoImport._

import sbtrelease._
import sbtrelease.ReleasePlugin.autoImport._

object BlazePlugin extends AutoPlugin {

  object autoImport {
    val blazeMimaVersion = settingKey[Option[String]]("Version to target for MiMa compatibility")
    val jvmTarget = TaskKey[String]("jvm-target-version", "Defines the target JVM version for object files.")
  }
  import autoImport._

  override def trigger = allRequirements

  override def requires = MimaPlugin && ReleasePlugin

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    // Override rig's default of the Travis build number being the bugfix number
    releaseVersion := { ver =>
      Version(ver).map(_.withoutQualifier.string).getOrElse(versionFormatError(ver))
    },
    scalaVersion := (sys.env.get("TRAVIS_SCALA_VERSION") orElse sys.env.get("SCALA_VERSION") getOrElse "2.12.8"),
    jvmTarget := {
      VersionNumber(scalaVersion.value).numbers match {
        case Seq(2, 10, _*) => "1.7"
        case _ => "1.8"
      }
    },
    // Setting Key To Show Mima Version Checked
    blazeMimaVersion := mimaPreviousVersion(version.value, scalaVersion.value),

    javacOptions ++= Seq(
      "-source", jvmTarget.value,
      "-target", jvmTarget.value
    ),
    fork in run := true,
    mimaFailOnProblem := blazeMimaVersion.value.isDefined,
    mimaPreviousArtifacts := mimaPreviousVersion(version.value, scalaVersion.value).map { pv =>
      organization.value %% moduleName.value % pv
    }.toSet,
    mimaBinaryIssueFilters ++= Seq()
  )


  def mimaPreviousVersion(currentVersion: String, scalaVersion: String): Option[String] = {
    val Version = """(\d+)\.(\d+)\.(\d+).*""".r
    val Version(x, y, z) = currentVersion
    if (z == "0") None
    else Some(s"$x.$y.${z.toInt - 1}")
  }

  def log4sVersion(sv: String) = CrossVersion.binaryScalaVersion(sv) match {
    case "2.13.0-M5" => "1.7.0"
    case _ => "1.8.2"
  }

  lazy val logbackClassic      = "ch.qos.logback"             %  "logback-classic"     % "1.2.3"
  lazy val twitterHPACK        = "com.twitter"                %  "hpack"               % "1.0.2"
  lazy val asyncHttpClient     = "org.asynchttpclient"        %  "async-http-client"   % "2.10.4"
  def log4s(sv: String)        = "org.log4s"                  %% "log4s"               % log4sVersion(sv)
  lazy val scalacheck          = "org.scalacheck"             %% "scalacheck"          % "1.14.0"
  lazy val specs2              = "org.specs2"                 %% "specs2-core"         % "4.5.1"
  lazy val specs2Mock          = "org.specs2"                 %% "specs2-mock"         % specs2.revision
  lazy val specs2Scalacheck    = "org.specs2"                 %% "specs2-scalacheck"   % specs2.revision
  // Needed for Http2 support until implemented in the JDK
  lazy val alpn_api            = "org.eclipse.jetty.alpn"     % "alpn-api"             % "1.1.3.v20160715"
  // Note that the alpn_boot version is JVM version specific. Check the docs if getting weird errors.
  // Also note that only java8 and above has the require cipher suite for http2.
  lazy val alpn_boot           = "org.eclipse.jetty"          % "jetty-alpn-openjdk8-client" % "9.4.22.v20191022"

}
