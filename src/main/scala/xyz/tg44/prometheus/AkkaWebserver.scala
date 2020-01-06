package xyz.tg44.prometheus

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

object AkkaWebserver {

  private val logger = LoggerFactory.getLogger("AkkaWebserver")

  def startWebserver(routes: Route)(
      implicit actorSystem: ActorSystem,
      materializer: Materializer,
      executionContext: ExecutionContext
  ): Future[Http.ServerBinding] = {
    Http()
      .bindAndHandle(routes, "0.0.0.0", 9000)
      .map { binding =>
        logger.info(s"Server started on 0.0.0.0:9000")
        setupShutdownHook(binding)
        binding
      }
  }

  private def setupShutdownHook(
      server: Http.ServerBinding
  )(implicit system: ActorSystem, executionContext: ExecutionContext): Unit = {
    import concurrent.duration._
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "http_shutdown") { () =>
      logger.info("Service shutting down...")
      server.terminate(hardDeadline = 10.seconds).map(_ => Done)
    }
  }
}
