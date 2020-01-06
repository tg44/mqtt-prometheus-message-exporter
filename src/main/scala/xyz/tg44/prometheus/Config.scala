package xyz.tg44.prometheus

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.util.{Try, Using}
import spray.json._

object Config {
  private val logger = LoggerFactory.getLogger("Config")
  private val config = ConfigFactory.load()

  def getConfigFileContent(): AppConfig = {
    def handleFileReadError(ex: Throwable): Nothing = {
      logger.error("There was an error during the config file read!", ex)
      throw ex
    }
    def handleJsonParseError(ex: Throwable): Nothing = {
      logger.error("There was an error during the config json parse!", ex)
      throw ex
    }
    def handleJsonConvertError(ex: Throwable): Nothing = {
      logger.error("There was an error during the config json structure parse!", ex)
      throw ex
    }
    def parseJson(s: String) = {
      Try(s.parseJson).fold(handleJsonParseError, convertJson)
    }
    def convertJson(j: JsValue) = {
      Try(j.convertTo[AppConfig]).fold(handleJsonConvertError, identity)
    }
    val fileContent = Using(Source.fromFile(config.getString("configFileLocation")))(_.mkString)
    val res = fileContent.fold(handleFileReadError, parseJson)
    logger.info("Config file successfully parsed!")
    res
  }

  import spray.json.DefaultJsonProtocol._
  case class AppConfig(mqtt: MqttConfig, patterns: Seq[PatternConf])
  case class MqttConfig(host: String, port: Int)
  case class PatternConf(prefix: String, pattern: String)

  implicit val patternConfigFormat: RootJsonFormat[PatternConf] = jsonFormat2(PatternConf)
  implicit val mqttConfigFormat: RootJsonFormat[MqttConfig] = jsonFormat2(MqttConfig)
  implicit val appConfigFormat: RootJsonFormat[AppConfig] = jsonFormat2(AppConfig)
}
