
name := "mqtt-prometheus-message-exporter"

version := "0.1"

scalaVersion := "2.13.1"



resolvers += Resolver.bintrayRepo("akka", "snapshots")
libraryDependencies ++= {
  val akkaHttpV = "10.1.11"
  val akkaV     = "2.5.27"
  Seq(
    //akka + json
    "com.typesafe.akka"  %% "akka-http"            % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka"  %% "akka-actor-typed"     % akkaV,
    "com.typesafe.akka"  %% "akka-stream"          % akkaV,
    "com.typesafe.akka"  %% "akka-stream-typed"    % akkaV,
    "com.lightbend.akka" %% "akka-stream-alpakka-mqtt-streaming" % "2.0.2",
    //misc
    "com.github.pureconfig" %% "pureconfig"  % "0.11.1",
    "org.scalatest"           %% "scalatest"      % "3.0.8" % Test,
    //logging
    "net.logstash.logback" % "logstash-logback-encoder" % "6.1",
    "ch.qos.logback"       % "logback-classic"          % "1.2.3",
    "org.slf4j"            % "jul-to-slf4j"             % "1.7.28",
    "com.typesafe.akka"    %% "akka-slf4j"              % akkaV,
    "org.codehaus.janino"  % "janino"                   % "3.1.0"
  )
}

enablePlugins(JavaAppPackaging)
