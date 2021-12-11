
name := "mqtt-prometheus-message-exporter"

version := "0.1"

scalaVersion := "2.13.7"



resolvers += Resolver.bintrayRepo("akka", "snapshots")
libraryDependencies ++= {
  val akkaHttpV = "10.2.7"
  val akkaV     = "2.6.17"
  Seq(
    //akka + json
    "com.typesafe.akka"  %% "akka-http"            % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka"  %% "akka-actor-typed"     % akkaV,
    "com.typesafe.akka"  %% "akka-stream"          % akkaV,
    "com.typesafe.akka"  %% "akka-stream-typed"    % akkaV,
    "com.lightbend.akka" %% "akka-stream-alpakka-mqtt-streaming" % "3.0.4",
    //misc
    "com.github.pureconfig" %% "pureconfig"  % "0.17.1",
    "org.scalatest"           %% "scalatest"      % "3.0.9" % Test,
    //logging
    "net.logstash.logback" % "logstash-logback-encoder" % "7.0.1",
    "ch.qos.logback"       % "logback-classic"          % "1.2.7",
    "org.slf4j"            % "jul-to-slf4j"             % "1.7.32",
    "com.typesafe.akka"    %% "akka-slf4j"              % akkaV,
    "org.codehaus.janino"  % "janino"                   % "3.1.6"
  )
}

enablePlugins(JavaAppPackaging)
