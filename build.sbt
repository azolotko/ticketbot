name := "ticketbot"

version := "0.1.0"

licenses := Seq(
  "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html"))

scalaVersion := "2.12.8"

lazy val pureconfigVersion = "0.10.2"

libraryDependencies ++= Seq(
  "software.amazon.awssdk" % "aws-sdk-java" % "2.5.14",
  "com.typesafe.akka" %% "akka-stream" % "2.5.21",
  "com.typesafe.slick" %% "slick" % "3.3.0",
  "com.h2database" % "h2" % "1.4.197",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.github.pureconfig" %% "pureconfig" % pureconfigVersion,
  "com.github.pureconfig" %% "pureconfig-enumeratum" % pureconfigVersion,
)

lazy val akkaChromeDevtools = RootProject(file("../akka-chrome-devtools"))

val ticketbot = (project in file(".")).dependsOn(akkaChromeDevtools)

mainClass in (Compile, run) := Some("me.zolotko.ticketbot.TicketBot")
