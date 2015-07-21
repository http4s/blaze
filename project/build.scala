import sbt._
import Keys._
import scala.util.Properties

import spray.revolver.RevolverPlugin._

object ApplicationBuild extends Build {

  /* Projects */
  lazy val blaze = project
                    .in(file("."))
                    .settings(buildSettings :+ dontPublish:_*)
                    .aggregate(core, http, examples)

  lazy val core = Project("blaze-core",
                      file("core"),
                      settings = buildSettings ++ dependencies)

  lazy val http = Project("blaze-http",
                    file("http"),
                    settings = buildSettings ++ dependencies ++ Seq(
                      libraryDependencies ++= Seq(http4sWebsocket,
                                                  twitterHAPCK,
                                                  alpn_api),
                      libraryDependencies ++= (scalaBinaryVersion.value match {
                        case "2.10" => Seq.empty
                        case "2.11" => Seq(scalaXml)
                      })
                    )
                  ).dependsOn(core % "test->test;compile->compile")

  lazy val examples = Project("blaze-examples",
                    file("examples"),
                    settings = buildSettings ++
                               Revolver.settings ++
                      Seq(
                        dontPublish,
                        libraryDependencies += logbackClassic,
                        libraryDependencies += alpn_boot,

                        // Adds ALPN to the boot classpath for Http2 support
                        javaOptions in run <++= (managedClasspath in Runtime) map { attList =>
                          for {
                            file <- attList.map(_.data)
                            path = file.getAbsolutePath if path.contains("jetty.alpn")
                          } yield { println(path); "-Xbootclasspath/p:" + path}
                        }
                      )
                  ).dependsOn(http)

  /* Don't publish setting */
  val dontPublish = packagedArtifacts := Map.empty

  val JvmTarget = "1.7"

  val primaryScalaVerison = "2.11.7"

  /* global build settings */
  lazy val buildSettings = Defaults.defaultSettings ++ publishing ++ Seq(
    organization := "org.http4s",

    version := "0.10.0-SNAPSHOT",

    scalaVersion := primaryScalaVerison,

    crossScalaVersions := Seq("2.10.5", primaryScalaVerison),

    description := "NIO Framework for Scala",

    homepage := Some(url("https://github.com/http4s/blaze")),

    startYear := Some(2014),

    licenses := Seq(("Apache 2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),

    scmInfo := Some(
      ScmInfo(
        url("https://github.com/http4s/blaze"),
        "scm:git:https://github.com/http4s/blaze.git",
        Some("scm:git:git@github.com:http4s/blaze.git")
      )
    ),

    scalacOptions in ThisBuild ++= Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-language:implicitConversions",
     s"-target:jvm-${JvmTarget}"
    ),

//    javaOptions in run += "-Djavax.net.debug=all",    // SSL Debugging
//    javaOptions in run += "-Dcom.sun.net.ssl.enableECC=false",
//    javaOptions in run += "-Djsse.enableSNIExtension=false",
    fork in run := true,
    resolvers ++= Seq(
      Resolver.sonatypeRepo("snapshots"),
      "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases" // needed for specs2 streamz dep
    )
  )

  /* dependencies */
  lazy val dependencies = Seq(
    libraryDependencies += specs2 % "test",
    libraryDependencies += logbackClassic % "test",
    libraryDependencies += log4s
  )

  lazy val specs2              = "org.specs2"                 %% "specs2-core"         % "3.3"
  lazy val http4sWebsocket     = "org.http4s"                 %% "http4s-websocket"    % "0.1.1"
  lazy val logbackClassic      = "ch.qos.logback"             %  "logback-classic"     % "1.1.3"
  lazy val log4s               = "org.log4s"                  %% "log4s"               % "1.1.5"
  lazy val scalaXml =            "org.scala-lang.modules"     %% "scala-xml"           % "1.0.3"
  lazy val twitterHAPCK        = "com.twitter"                %  "hpack"               % "v1.0.1"


  // Needed for Http2 support until implemented in the JDK
  lazy val alpn_api            = "org.eclipse.jetty.alpn"     % "alpn-api"             % "1.1.2.v20150522"

  // Note that the alpn_boot version is JVM version specific. Check the docs if getting weird errors.
  // Also note that only java8 and above has the require cipher suite for http2.
  lazy val alpn_boot           = "org.mortbay.jetty.alpn"     % "alpn-boot"            % "8.1.3.v20150130"

  /* publishing */
  lazy val publishing = Seq(
    publishMavenStyle := true,
    publishTo <<= version { (v: String) =>
      val nexus = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT")) Some(
        "snapshots" at nexus + "content/repositories/snapshots"
      )
      else Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },

    Seq("SONATYPE_USER", "SONATYPE_PASS") map Properties.envOrNone match {
      case Seq(Some(user), Some(pass)) =>
        credentials += Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
      case _ => credentials in ThisBuild ~= identity
    },

    pomExtra := (
      <developers>
        <developer>
          <id>bryce-anderson</id>
          <name>Bryce L. Anderson</name>
          <email>bryce.anderson22@gamil.com</email>
        </developer>
        <developer>
          <id>rossabaker</id>
          <name>Ross A. Baker</name>
          <email>ross@rossabaker.com</email>
        </developer>
      </developers>
    )
  )

}
