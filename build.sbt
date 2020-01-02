name := "mqtt-prometheus-message-exporter"

version := "0.1"

scalaVersion := "2.13.1"

libraryDependencies += "com.typesafe.akka" %% "akka-actor-typed" % "2.6.1"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.6.1"
libraryDependencies += "org.typelevel" %% "cats-effect" % "2.0.0"
