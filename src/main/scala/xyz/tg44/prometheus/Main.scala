package xyz.tg44.prometheus

import akka.Done
import akka.actor.ActorSystem
import akka.custom.PullableSink
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.stream.alpakka.mqtt.streaming.scaladsl.{ActorMqttClientSession, Mqtt}
import akka.stream.alpakka.mqtt.streaming._
import akka.stream.scaladsl.{Keep, Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete, Tcp}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.ByteString
import org.slf4j.LoggerFactory
import xyz.tg44.prometheus.Config.AppConfig
import xyz.tg44.prometheus.exporter.Registry.{Line, MetricMeta}
import xyz.tg44.prometheus.exporter.{PrometheusRenderer, Registry}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object Main extends App {
  LogBridge.initLogBridge()

  implicit val actorSystem = ActorSystem("mqttExporter")
  implicit val mat = ActorMaterializer()
  implicit val ec = ExecutionContext.global
  implicit val metricsRegistry = new Registry()

  private val logger = LoggerFactory.getLogger("Main")

  val appConf: AppConfig = Config.getConfigFileContent().fold{stopTheApp(); throw new Exception("Config read error")}{identity}

  val pathResolver: String => Option[MetricMeta] = PatternUtils.metaBuilder(appConf.patterns)

  var mqttSessionSettings = MqttSessionSettings()
  appConf.mqtt.maxPacketSize.foreach(maxPacketSize =>
    mqttSessionSettings = mqttSessionSettings.withMaxPacketSize(maxPacketSize))

  val session = ActorMqttClientSession(mqttSessionSettings)
  val connection = Tcp().outgoingConnection(appConf.mqtt.host, appConf.mqtt.port)
  val ((commands, done), pullable): ((SourceQueueWithComplete[Command[Nothing]], Future[Done]), SinkQueueWithCancel[String]) =
    Source
      .queue(2, OverflowStrategy.fail)
      .via(
        Mqtt
        .clientSessionFlow(session, ByteString("prom-mqtt"))
        .join(connection)
      )
      .watchTermination()(Keep.both)
      //.map{e => logger.info(e.toString); e}
      .collect {
        case Right(Event(p: Publish, _)) => (p.topicName, p.payload.utf8String)
      }
      .mapConcat(c => PatternUtils.flatten(c._1, c._2))
      .map(c => pathResolver(c._1) -> c._2)
      .collect{
        case (Some(meta), value) => (Line(meta, value, None), false)
      }
      .via(Registry.registryFlow)
      .map(lineMap => PrometheusRenderer.render(lineMap.values))
      .toMat(Sink.fromGraph(new PullableSink[String]()))(Keep.both).run


  private val connect: Connect =
    if (appConf.mqtt.username.isDefined && appConf.mqtt.password.isDefined)
      Connect("prom-mqtt", ConnectFlags.CleanSession, appConf.mqtt.username.get, appConf.mqtt.password.get)
    else
      Connect("prom-mqtt", ConnectFlags.CleanSession)
  commands.offer(Command(connect))
  appConf.patterns.foreach{ p =>
    val topicToConnect = PatternUtils.topicFromPattern(p.pattern)
    commands.offer(Command(Subscribe(topicToConnect)))
    logger.info(s"Subscribe to '$topicToConnect' topic")
  }


  import akka.http.scaladsl.server.Directives._
  val routes: Route = get{
    path("metrics") {
      onComplete(pullable.pull()) {
        case Success(Some(ret)) =>
          complete(ret)
        case Success(None) =>
          logger.info("We have no data yet! This should not happen!")
          complete(StatusCodes.InternalServerError)
        case Failure(ex) =>
          logger.error("Exception on the stream!", ex)
          complete(StatusCodes.InternalServerError)
      }
    }
  }


  AkkaWebserver.startWebserver(routes).recoverWith { case _ => stopTheApp() }

  done.onComplete{
    case Success(value) =>
      logger.warn("The stream terminated!")
      stopTheApp()
    case Failure(ex) =>
      logger.warn("The stream terminated with an exception!", ex)
      stopTheApp()
  }

  private def stopTheApp() = actorSystem.terminate.map(_ => System.exit(2))
}
