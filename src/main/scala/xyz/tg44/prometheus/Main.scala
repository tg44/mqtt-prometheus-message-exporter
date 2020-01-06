package xyz.tg44.prometheus

import akka.NotUsed
import akka.actor.ActorSystem
import akka.custom.{HoldWithInitial, PullableSink}
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, ResponseEntity, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.stream.alpakka.mqtt.streaming.scaladsl.{ActorMqttClientSession, Mqtt}
import akka.stream.alpakka.mqtt.streaming.{Command, Connect, ConnectFlags, Event, MqttCodec, MqttSessionSettings, Publish, Subscribe}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import org.slf4j.LoggerFactory
import xyz.tg44.prometheus.exporter.{PrometheusRenderer, Registry}
import xyz.tg44.prometheus.exporter.Registry.{Line, MetricMeta}
import xyz.tg44.prometheus.mqtt.Utils

import concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object Main extends App {
  LogBridge.initLogBridge()

  implicit val actorSystem = ActorSystem("mqttExporter")
  implicit val mat = ActorMaterializer()
  implicit val ec = ExecutionContext.global
  implicit val metricsRegistry = new Registry()

  private val logger = LoggerFactory.getLogger("Main")

  val appConf = Config.getConfigFileContent()

  val pathResolver: String => Option[MetricMeta] = Utils.metaBuilder(appConf.patterns)

  val session = ActorMqttClientSession(MqttSessionSettings())
  val connection = Tcp().outgoingConnection(appConf.mqtt.host, appConf.mqtt.port)
  val (commands, pullable): (SourceQueueWithComplete[Command[Nothing]], SinkQueueWithCancel[String]) =
    Source
      .queue(2, OverflowStrategy.fail)
      .via(
        Mqtt
        .clientSessionFlow(session, ByteString("prom-mqtt"))
        .join(connection)
      )
      //.map{e => logger.info(e.toString); e}
      .collect {
        case Right(Event(p: Publish, _)) => (p.topicName, p.payload.utf8String)
      }
      .mapConcat(c => Utils.flatten(c._1, c._2))
      .map(c => pathResolver(c._1) -> c._2)
      .collect{
        case (Some(meta), value) => (Line(meta, value, None), false)
      }
      .via(Registry.registryFlow)
      .via(new HoldWithInitial[Map[MetricMeta, Line]](Map.empty))
      .map(lineMap => PrometheusRenderer.render(lineMap.values))
      .toMat(Sink.fromGraph(new PullableSink[String]()))(Keep.both).run


  commands.offer(Command(Connect("prom-mqtt", ConnectFlags.CleanSession)))
  appConf.patterns.foreach{ case p =>
    val topicToConnect = Utils.topicFromPattern(p.pattern)
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
          logger.info("We have no data yet!")
          complete(StatusCodes.InternalServerError)
        case Failure(ex) =>
          logger.error("Exception on the stream!", ex)
          complete(StatusCodes.InternalServerError)
        case other =>
          logger.error(s"We have no idea what happened: $other")
          complete(StatusCodes.InternalServerError)
      }
    }
  }

  (for {
    _ <- AkkaWebserver.startWebserver(routes)
  } yield ()).recoverWith { case _ => actorSystem.terminate.map(_ => System.exit(2)) }

}
