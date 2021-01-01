package xyz.tg44.prometheus

import akka.Done
import akka.actor.ActorSystem
import akka.stream.{Materializer, OverflowStrategy, TLSClientAuth, TLSProtocol, TLSRole}
import akka.stream.alpakka.mqtt.streaming.{Command, Connect, ConnectFlags, Event, MqttSessionSettings, Publish, Subscribe}
import akka.stream.alpakka.mqtt.streaming.scaladsl.{ActorMqttClientSession, Mqtt}
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Sink, Source, SourceQueueWithComplete, TLS, Tcp}
import akka.util.ByteString
import com.typesafe.sslconfig.akka.AkkaSSLConfig

import javax.net.ssl.{KeyManager, SSLContext, X509TrustManager}
import java.security.cert.X509Certificate
import com.typesafe.sslconfig.ssl.ClientAuth
import org.slf4j.LoggerFactory
import xyz.tg44.prometheus.Config.AppConfig
import xyz.tg44.prometheus.exporter.Registry.{Line, MetricMeta}

import scala.concurrent.{ExecutionContext, Future}

class MqttHelper(appConf: AppConfig)(implicit system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) {

  private val clientName = "prom-mqtt"
  private val logger = LoggerFactory.getLogger("MqttFlow")

  def getSource(): Source[(Line, Boolean), (SourceQueueWithComplete[Command[Nothing]], Future[Done])] = {
    val pathResolver: String => Option[MetricMeta] = PatternUtils.metaBuilder(appConf.patterns)

    val mqttSessionSettings = appConf.mqtt.maxPacketSize
      .map(maxPacketSize => MqttSessionSettings().withMaxPacketSize(maxPacketSize))
      .getOrElse(MqttSessionSettings())

    val session = ActorMqttClientSession(mqttSessionSettings)
    val connection = if(appConf.mqtt.useTls.exists(identity)) {
      tlsStage(TLSRole.client).join(Tcp().outgoingConnection(appConf.mqtt.host, appConf.mqtt.port))
    } else {
      Tcp().outgoingConnection(appConf.mqtt.host, appConf.mqtt.port)
    }

    Source
      .queue(2, OverflowStrategy.backpressure)
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
    Source(appConf.patterns.toList).mapAsync(1){ p =>
      val topicToConnect = PatternUtils.topicFromPattern(p.pattern)
      val ret = commands.offer(Command(Subscribe(topicToConnect)))
      ret.foreach(_ => logger.info(s"Subscribe to '$topicToConnect' topic"))
      ret
    }.runWith(Sink.ignore)
  }

  //https://stackoverflow.com/a/43264311
  def tlsStage(role: TLSRole)(implicit system: ActorSystem) = {
    val sslConfig = AkkaSSLConfig.get(system)
    val config = sslConfig.config

    // create a ssl-context that ignores self-signed certificates
    implicit val sslContext: SSLContext = {
      object WideOpenX509TrustManager extends X509TrustManager {
        override def checkClientTrusted(chain: Array[X509Certificate], authType: String) = ()
        override def checkServerTrusted(chain: Array[X509Certificate], authType: String) = ()
        override def getAcceptedIssuers = Array[X509Certificate]()
      }

      val context = SSLContext.getInstance("TLS")
      context.init(Array[KeyManager](), Array(WideOpenX509TrustManager), null)
      context
    }
    // protocols
    val defaultParams = sslContext.getDefaultSSLParameters()
    val defaultProtocols = defaultParams.getProtocols()
    val protocols = sslConfig.configureProtocols(defaultProtocols, config)
    defaultParams.setProtocols(protocols)

    // ciphers
    val defaultCiphers = defaultParams.getCipherSuites()
    val cipherSuites = sslConfig.configureCipherSuites(defaultCiphers, config)
    defaultParams.setCipherSuites(cipherSuites)

    val firstSession = new TLSProtocol.NegotiateNewSession(None, None, None, None)
      .withCipherSuites(cipherSuites: _*)
      .withProtocols(protocols: _*)
      .withParameters(defaultParams)

    val clientAuth = getClientAuth(config.sslParametersConfig.clientAuth)
    clientAuth map { firstSession.withClientAuth(_) }

    val tls = TLS.apply(sslContext, firstSession, role)

    val pf: PartialFunction[TLSProtocol.SslTlsInbound, ByteString] = {
      case TLSProtocol.SessionBytes(_, sb) => ByteString.fromByteBuffer(sb.asByteBuffer)
    }

    val tlsSupport = BidiFlow.fromFlows(
      Flow[ByteString].map(TLSProtocol.SendBytes),
      Flow[TLSProtocol.SslTlsInbound].collect(pf));

    tlsSupport.atop(tls);
  }

  def getClientAuth(auth: ClientAuth) = {
    if (auth.equals(ClientAuth.want)) {
      Some(TLSClientAuth.want)
    } else if (auth.equals(ClientAuth.need)) {
      Some(TLSClientAuth.need)
    } else if (auth.equals(ClientAuth.none)) {
      Some(TLSClientAuth.none)
    } else {
      None
    }
  }
}
