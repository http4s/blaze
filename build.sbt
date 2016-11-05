import scala.util.Properties

/* Projects */
lazy val blaze = project
  .in(file("."))
  .settings(dontPublish:_*)
  .aggregate(core, http, examples)

lazy val core = Project("blaze-core",
  file("core"),
  settings = publishing ++ mimaSettings ++ dependencies)

lazy val http = Project("blaze-http",
  file("http"),
  settings = publishing ++ mimaSettings ++ dependencies ++ Seq(
    libraryDependencies ++= Seq(http4sWebsocket,
      twitterHPACK,
      alpn_api),
    libraryDependencies ++= {
      VersionNumber(scalaBinaryVersion.value).numbers match {
        case Seq(2, 10) => Seq.empty
        case _ => Seq(scalaXml)
      }
    }
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

val jvmTarget = TaskKey[String]("jvm-target-version", "Defines the target JVM version for object files.")
jvmTarget in ThisBuild := {
  VersionNumber(scalaVersion.value).numbers match {
    case Seq(2, 10, _*) => "1.7"
    case _ => "1.8"
  }
}

/* global build settings */
organization in ThisBuild := "org.http4s"

version in ThisBuild := "0.13.0-SNAPSHOT"

scalaVersion in ThisBuild := "2.11.8"

crossScalaVersions in ThisBuild := Seq("2.10.6", scalaVersion.value, "2.12.0")

description in ThisBuild := "NIO Framework for Scala"

homepage in ThisBuild := Some(url("https://github.com/http4s/blaze"))

startYear in ThisBuild := Some(2014)

licenses in ThisBuild := Seq(("Apache 2.0", url("http://www.apache.org/licenses/LICENSE-2.0")))

scmInfo in ThisBuild := Some(
  ScmInfo(
    url("https://github.com/http4s/blaze"),
    "scm:git:https://github.com/http4s/blaze.git",
    Some("scm:git:git@github.com:http4s/blaze.git")
  )
)

javacOptions in ThisBuild ++= Seq(
  "-source", jvmTarget.value,
  "-target", jvmTarget.value
)

scalacOptions in ThisBuild ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:implicitConversions",
  s"-target:jvm-${jvmTarget.value}"
)

fork in run := true

lazy val mimaSettings = Seq(
  failOnProblem <<= version(compatibleVersion(_).isDefined),
  previousArtifact <<= (version, organization, scalaBinaryVersion,   moduleName)((ver, org, binVer, mod) => compatibleVersion(ver) map {
    org % s"${mod}_${binVer}" % _
  })
)

/* dependencies */
lazy val dependencies = Seq(
  libraryDependencies += specs2 % "test",
  libraryDependencies += logbackClassic % "test",
  libraryDependencies += log4s
)

lazy val specs2              = "org.specs2"                 %% "specs2-core"         % "3.8.6"
lazy val http4sWebsocket     = "org.http4s"                 %% "http4s-websocket"    % "0.1.6"
lazy val logbackClassic      = "ch.qos.logback"             %  "logback-classic"     % "1.1.3"
lazy val log4s               = "org.log4s"                  %% "log4s"               % "1.3.3"
lazy val scalaXml =            "org.scala-lang.modules"     %% "scala-xml"           % "1.0.5"
lazy val twitterHPACK        = "com.twitter"                %  "hpack"               % "v1.0.1"


// Needed for Http2 support until implemented in the JDK
lazy val alpn_api            = "org.eclipse.jetty.alpn"     % "alpn-api"             % "1.1.2.v20150522"

// Note that the alpn_boot version is JVM version specific. Check the docs if getting weird errors.
// Also note that only java8 and above has the require cipher suite for http2.
lazy val alpn_boot           = "org.mortbay.jetty.alpn"     % "alpn-boot"            % "8.1.7.v20160121"

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

def compatibleVersion(version: String) = {
  val currentVersionWithoutSnapshot = version.replaceAll("-SNAPSHOT$", "")
  val (targetMajor, targetMinor) = extractApiVersion(version)
  val targetVersion = s"${targetMajor}.${targetMinor}.0"
  if (targetVersion != currentVersionWithoutSnapshot) Some(targetVersion)
  else None
}

def extractApiVersion(version: String) = {
  val VersionExtractor = """(\d+)\.(\d+)\..*""".r
  version match {
    case VersionExtractor(major, minor) => (major.toInt, minor.toInt)
  }
}
