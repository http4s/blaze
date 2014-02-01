import sbt._
import Keys._

import spray.revolver.RevolverPlugin._

object ApplicationBuild extends Build {

  lazy val buildSettings = Defaults.defaultSettings ++ Revolver.settings ++ Seq(
    organization := "brycea",
    version := "0.0.1-SNAPSHOT",
    scalaVersion in ThisBuild := "2.10.3",

    libraryDependencies += scalatest % "test",
    libraryDependencies += scalameter,
    libraryDependencies += scalaloggingSlf4j,
    libraryDependencies += logbackClassic,
    libraryDependencies += npn_api,
    libraryDependencies += npn_boot,

    mainClass in Revolver.reStart := Some("blaze.examples.NIO1HttpServer"),
    fork := true,
//    javaOptions in run += "-Djavax.net.debug=all",

      // Adds NPN to the boot classpath for Spdy support
    javaOptions in run <++= (managedClasspath in Runtime) map { attList =>
      for {
        file <- attList.map(_.data)
        path = file.getAbsolutePath if path.contains("jetty.npn")
      } yield { println(path); "-Xbootclasspath/p:" + path}
    }

  )

  lazy val main = Project("blaze",
                    new File("."),
                    settings = buildSettings
    )
    
  lazy val http4s = Project("http4s",
                      new File("http4s"),
                      settings = buildSettings
              ).dependsOn(main, http4score, http4sdsl)
   
   lazy val scalatest  = "org.scalatest"  %% "scalatest" % "2.0.RC3"
   lazy val scalameter = "com.github.axel22" % "scalameter_2.10" % "0.4"
   
   lazy val scalaloggingSlf4j   = "com.typesafe"   %% "scalalogging-slf4j" % "1.0.1"
   lazy val logbackClassic      = "ch.qos.logback" %  "logback-classic"    % "1.0.9"
   
   lazy val http4score = ProjectRef(uri("git://github.com/http4s/http4s.git"), "core")
   lazy val http4sdsl = ProjectRef(uri("git://github.com/http4s/http4s.git"), "dsl")


  // Needed for Spdy Support. Perhaps it should be a sub-project?
  // Interesting note: Http2.0 will use the TSLALPN extension which, unfortunately,
  // is also not implemented in java SSL yet.
  lazy val npn_api             = "org.eclipse.jetty.npn"     % "npn-api"  % npn_version
  lazy val npn_boot            = "org.mortbay.jetty.npn"     % "npn-boot" % npn_version

  lazy val npn_version = "8.1.2.v20120308"
            
}
