name := "at-most-once-consumer"

version := "0.1"

scalaVersion := "2.12.11"

val akkaVersion = "2.5.23"
val alpakkaVersion = "2.0.2"

enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)


lazy val common = (project in file("common"))
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-kafka" % alpakkaVersion,

    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
  ))

val consumerDockerSettings = Vector(
  imageNames in docker := Vector (
    ImageName(repository = name.value, tag = Some("latest"))
  ),
  dockerfile in docker := {
    val appDir: File = stage.value
    val targetDir = "/app"

    new Dockerfile {
      from("openjdk:8-jre")
      entryPoint(s"$targetDir/bin/${executableScriptName.value}")
      copy(appDir, targetDir, chown = "daemon:daemon")
    }
  }
)

lazy val consumer = (project in file("consumer"))
  .dependsOn(common)
  .enablePlugins(JavaAppPackaging, sbtdocker.DockerPlugin)
  .settings(consumerDockerSettings: _*)

val producerDockerSettings = Vector(
  imageNames in docker := Vector (
    ImageName(repository = name.value, tag = Some("latest"))
  ),
  dockerfile in docker := {
    val appDir: File = stage.value
    val targetDir = "/app"

    new Dockerfile {
      from("openjdk:8-jre")
      entryPoint(s"$targetDir/bin/${executableScriptName.value}")
      copy(appDir, targetDir, chown = "daemon:daemon")
    }
  }
)

lazy val producer = (project in file("producer"))
  .dependsOn(common)
  .enablePlugins(JavaAppPackaging, sbtdocker.DockerPlugin)
  .settings(producerDockerSettings: _*)