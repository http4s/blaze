import sbt._
import Keys._

//import spray.revolver.RevolverPlugin._

object ApplicationBuild extends Build {

  /* Projects */
  lazy val main = Project("blaze",
                    new File("."),
                    settings = buildSettings
                  )

  /* global build settings */
  //lazy val buildSettings = Defaults.defaultSettings ++  publishing ++ Revolver.settings ++ Seq(
  lazy val buildSettings = Defaults.defaultSettings ++ 
                           dependencies ++
                           publishing ++ Seq(
    organization := "org.http4s",

    version := "0.1.0-SNAPSHOT",

    scalaVersion := "2.10.4",

    description := "NIO Framework for Scala",

    homepage := Some(url("https://github.com/http4s/blaze")),

    startYear := Some(2014),

    licenses := Seq(
      ("BSD 2-clause", url("https://raw.github.com/http4s/http4s/develop/LICENSE"))
    ),

    scmInfo := Some(
      ScmInfo(
        url("https://github.com/http4s/blaze"),
        "scm:git:https://github.com/http4s/blaze.git",
        Some("scm:git:git@github.com:http4s/blaze.git")
      )
    ),

    //mainClass in Revolver.reStart := Some("org.http4s.blaze.examples.NIO1HttpServer"),
//    javaOptions in run += "-Djavax.net.debug=all",    // SSL Debugging
//    javaOptions in run += "-Dcom.sun.net.ssl.enableECC=false",
//    javaOptions in run += "-Djsse.enableSNIExtension=false",
    fork in run := true

      // Adds NPN to the boot classpath for Spdy support
//    javaOptions in run <++= (managedClasspath in Runtime) map { attList =>
//      for {
//        file <- attList.map(_.data)
//        path = file.getAbsolutePath if path.contains("jetty.npn")
//      } yield { println(path); "-Xbootclasspath/p:" + path}
//    }

  )

  /* dependencies */
  lazy val dependencies = Seq(
    libraryDependencies += scalatest % "test",
    libraryDependencies += scalameter,
    libraryDependencies += scalaloggingSlf4j,
    libraryDependencies += logbackClassic,
    libraryDependencies += npn_api,
    libraryDependencies += npn_boot
  )
   
   lazy val scalatest  = "org.scalatest"  %% "scalatest" % "2.0.RC3"
   lazy val scalameter = "com.github.axel22" % "scalameter_2.10" % "0.4"
   
   lazy val scalaloggingSlf4j   = "com.typesafe"   %% "scalalogging-slf4j" % "1.0.1"
   lazy val logbackClassic      = "ch.qos.logback" %  "logback-classic"    % "1.0.9"


  // Needed for Spdy Support. Perhaps it should be a sub-project?
  // Interesting note: Http2.0 will use the TSLALPN extension which, unfortunately,
  // is also not implemented in java SSL yet.
  lazy val npn_api             = "org.eclipse.jetty.npn"     % "npn-api"  % npn_version
  lazy val npn_boot            = "org.mortbay.jetty.npn"     % "npn-boot" % npn_version

  lazy val npn_version = "8.1.2.v20120308"

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
      case _ =>
    }

    pomExtra := (
      <developers>
        <developer>
          <id>bryce-anderson</id>
          <name>Bryce L. Anderson</name>
          <email>bryce.anderson22@gamil.com</email>
          <!-- <url></url> -->
        </developer>
        <developer>
          <id>rossabaker</id>
          <name>Ross A. Baker</name>
          <email>baker@alumni.indiana.edu</email>
          <!-- <url></url> -->
        </developer>
      </developers>
    )
  )
            
}
