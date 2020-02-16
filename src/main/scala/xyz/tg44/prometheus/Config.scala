package xyz.tg44.prometheus

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.util.{Failure, Try, Using}
import spray.json._

object Config {
  private val logger = LoggerFactory.getLogger("Config")
  private val config = ConfigFactory.load()

  def getConfigFileContent(): Option[AppConfig] = {
    def parseJson(s: String) = {
      Try(s.parseJson)
    }
    def convertJson(j: JsValue) = {
      Try(j.convertTo[AppConfig])
    }
    val fileContent = Using(Source.fromFile(config.getString("configFileLocation")))(_.mkString)
    val conf = for {
      fc <- fileContent
      json <- parseJson(fc)
      conf <- convertJson(json)
    } yield {
      conf
    }
    conf match {
      case Failure(ex) => logger.error("There was an error during the config file read or parse!", ex)
      case _ => logger.info("Config file successfully parsed!")
    }
    conf.toOption
  }

  import spray.json.DefaultJsonProtocol._
  case class AppConfig(mqtt: MqttConfig, patterns: Seq[PatternConf], selfMetrics: Option[SelfMetricsConfig])
  case class MqttConfig(host: String, port: Int, username: Option[String], password: Option[String], maxPacketSize: Option[Int])
  case class PatternConf(prefix: String, pattern: String)
  case class SelfMetricsConfig(prefix: String)

  implicit val patternConfigFormat: RootJsonFormat[PatternConf] = jsonFormat2(PatternConf)
  implicit val mqttConfigFormat: RootJsonFormat[MqttConfig] = jsonFormat5(MqttConfig)
  implicit val selfMetricsConfigFormat: RootJsonFormat[SelfMetricsConfig] = jsonFormat1(SelfMetricsConfig)
  implicit val appConfigFormat: RootJsonFormat[AppConfig] = jsonFormat3(AppConfig)
}
