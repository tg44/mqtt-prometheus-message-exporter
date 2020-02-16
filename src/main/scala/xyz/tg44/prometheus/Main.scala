package xyz.tg44.prometheus

import akka.actor.ActorSystem
import akka.custom.PullableSink
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import org.slf4j.LoggerFactory
import xyz.tg44.prometheus.Config.AppConfig
import xyz.tg44.prometheus.exporter.{PrometheusRenderer, Registry}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object Main extends App {
  LogBridge.initLogBridge()

  implicit val actorSystem = ActorSystem("mqttExporter")
  implicit val mat = ActorMaterializer()
  implicit val ec = ExecutionContext.global
  implicit val metricsRegistry = new Registry()

  private val logger = LoggerFactory.getLogger("Main")

  val appConf: AppConfig = Config.getConfigFileContent().fold{stopTheApp(); throw new Exception("Config read error")}{identity}


  val mqttHelper = new MqttHelper(appConf)

  val source = appConf.selfMetrics.fold(
    mqttHelper.getSource()
  ){ mc =>
    val smh = new SelfMetricsHelper(mc)
    mqttHelper.getSource().merge(smh.uptimeSource, eagerComplete = true)
  }

  val ((commands, done), pullable) = source.via(Registry.registryFlow)
        .map(lineMap => PrometheusRenderer.render(lineMap.values))
        .toMat(Sink.fromGraph(new PullableSink[String]()))(Keep.both).run

  mqttHelper.startListening(commands)

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
