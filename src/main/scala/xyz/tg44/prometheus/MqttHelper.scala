package xyz.tg44.prometheus

import akka.Done
import akka.actor.ActorSystem
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.alpakka.mqtt.streaming.{Command, Connect, ConnectFlags, Event, MqttSessionSettings, Publish, Subscribe}
import akka.stream.alpakka.mqtt.streaming.scaladsl.{ActorMqttClientSession, Mqtt}
import akka.stream.scaladsl.{Keep, Source, SourceQueueWithComplete, Tcp}
import akka.util.ByteString
import org.slf4j.LoggerFactory
import xyz.tg44.prometheus.Config.AppConfig
import xyz.tg44.prometheus.exporter.Registry.{Line, MetricMeta}

import scala.concurrent.Future

class MqttHelper(appConf: AppConfig)(implicit system: ActorSystem, materializer: Materializer) {

  private val clientName = "prom-mqtt"
  private val logger = LoggerFactory.getLogger("MqttFlow")

  def getSource(): Source[(Line, Boolean), (SourceQueueWithComplete[Command[Nothing]], Future[Done])] = {
    val pathResolver: String => Option[MetricMeta] = PatternUtils.metaBuilder(appConf.patterns)

    val mqttSessionSettings = appConf.mqtt.maxPacketSize
      .map(maxPacketSize => MqttSessionSettings().withMaxPacketSize(maxPacketSize))
      .getOrElse(MqttSessionSettings())

    val session = ActorMqttClientSession(mqttSessionSettings)
    val connection = Tcp().outgoingConnection(appConf.mqtt.host, appConf.mqtt.port)

    Source
      .queue(2, OverflowStrategy.fail)
      .via(
        Mqtt
          .clientSessionFlow(session, ByteString(clientName))
          .join(connection)
      )
      .watchTermination()(Keep.both)
      //.map{e => logger.info(e.toString); e}
      .collect {
        case Right(Event(p: Publish, _)) => (p.topicName, p.payload.utf8String)
      }
      .mapConcat(c => PatternUtils.flatten(c._1, c._2))
      .map(c => pathResolver(c._1) -> c._2)
      .collect {
        case (Some(meta), value) => (Line(meta, value, None), false)
      }

  }

  def startListening(commands: SourceQueueWithComplete[Command[Nothing]]): Unit = {
    val connect: Connect =
      appConf.mqtt.username.flatMap(username =>
        appConf.mqtt.password.map(password =>
          Connect(clientName, ConnectFlags.CleanSession, username, password)
        )
      ).getOrElse(Connect(clientName, ConnectFlags.CleanSession))

    commands.offer(Command(connect))
    appConf.patterns.foreach { p =>
      val topicToConnect = PatternUtils.topicFromPattern(p.pattern)
      commands.offer(Command(Subscribe(topicToConnect)))
      logger.info(s"Subscribe to '$topicToConnect' topic")
    }
  }

}
