import scala.util.Properties

/* Global Build Settings */

lazy val commonSettings = Seq(
  organization := "org.http4s",
  version := "0.14.0-SNAPSHOT",
  scalaVersion := "2.11.11",
  crossScalaVersions := Seq("2.10.6", scalaVersion.value, "2.12.2"),
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
  javacOptions ++= Seq(
    "-source", jvmTarget.value,
    "-target", jvmTarget.value
  ),
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-unchecked",
    "-language:implicitConversions",
    s"-target:jvm-${jvmTarget.value}"
  ),
  fork in run := true

)

lazy val mimaSettings = Seq(
  mimaPreviousArtifacts := previousVersion(version.value).map { pv =>
    organization.value % (normalizedName.value + "_" + scalaBinaryVersion.value) % pv
  }.toSet,
  mimaBinaryIssueFilters ++= Seq()
)

/* dependencies */
lazy val dependencies = Seq(
  libraryDependencies += specs2 % "test",
  libraryDependencies += specs2Mock % "test",
  libraryDependencies += logbackClassic % "test",
  libraryDependencies += log4s
)

lazy val http4sWebsocket     = "org.http4s"                 %% "http4s-websocket"    % "0.1.6"
lazy val logbackClassic      = "ch.qos.logback"             %  "logback-classic"     % "1.2.3"
lazy val log4s               = "org.log4s"                  %% "log4s"               % "1.3.4"
lazy val scalaXml =            "org.scala-lang.modules"     %% "scala-xml"           % "1.0.6"
lazy val twitterHPACK        = "com.twitter"                %  "hpack"               % "1.0.2"

// Testing only dependencies
lazy val specs2              = "org.specs2"                 %% "specs2-core"         % "3.8.9"
lazy val specs2Mock          = "org.specs2"                 %% "specs2-mock"         % specs2.revision
lazy val asyncHttpClient     = "org.asynchttpclient"        %  "async-http-client"   % "2.0.32"


// Needed for Http2 support until implemented in the JDK
lazy val alpn_api            = "org.eclipse.jetty.alpn"     % "alpn-api"             % "1.1.3.v20160715"

// Note that the alpn_boot version is JVM version specific. Check the docs if getting weird errors.
// Also note that only java8 and above has the require cipher suite for http2.
lazy val alpn_boot           = "org.mortbay.jetty.alpn"     % "alpn-boot"            % "8.1.11.v20170118"

/* Publishing */
lazy val publishSettings = Seq(
  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
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

/* Don't publish setting */
val dontPublishSettings = packagedArtifacts := Map.empty


/* Projects */
lazy val blaze = project
  .in(file("."))
  .settings(dontPublishSettings)
  .aggregate(core, http, examples)

lazy val core = Project("blaze-core", file("core"))
  .settings(commonSettings)
  .settings(publishSettings ++ dependencies)
  .settings(mimaSettings)

lazy val http = Project("blaze-http", file("http"))
  .settings(commonSettings)
  .settings(publishSettings ++ dependencies)
  .settings(mimaSettings)
  .settings(
    libraryDependencies ++= Seq(
      http4sWebsocket,
      twitterHPACK,
      alpn_api,
      asyncHttpClient % "test"
    ),
    libraryDependencies ++= {
      VersionNumber(scalaBinaryVersion.value).numbers match {
        case Seq(2, 10) => Seq.empty
        case _ => Seq(scalaXml)
      }
    }
  )
  .dependsOn(core % "test->test;compile->compile")

lazy val examples = Project("blaze-examples",file("examples"))
  .settings(commonSettings)
  .settings(Revolver.settings)
  .settings(dontPublishSettings)
  .settings(mimaSettings)
  .settings(
    libraryDependencies += logbackClassic,
    libraryDependencies += alpn_boot,
    fork := true, // necessary to add ALPN classes to boot classpath

    // Adds ALPN to the boot classpath for Http2 support
    javaOptions in run ++= addAlpnPath((managedClasspath in Runtime).value)

  ).dependsOn(http)


/* Helper Functions */

def previousVersion(currentVersion: String): Option[String] = {
  val Version = """(\d+)\.(\d+)\.(\d+).*""".r
  val Version(x, y, z) = currentVersion
  if (z == "0") None
  else Some(s"$x.$y.${z.toInt - 1}")
}

def addAlpnPath(attList : Keys.Classpath): Seq[String] = {
  for {
    file <- attList.map(_.data)
    path = file.getAbsolutePath if path.contains("jetty.alpn")
  } yield { println(s"Alpn patth: $path"); "-Xbootclasspath/p:" + path}
}

addCommandAlias("validate", ";test ;mimaReportBinaryIssues")

val jvmTarget = TaskKey[String]("jvm-target-version", "Defines the target JVM version for object files.")
jvmTarget in ThisBuild := {
  VersionNumber(scalaVersion.value).numbers match {
    case Seq(2, 10, _*) => "1.7"
    case _ => "1.8"
  }
}
