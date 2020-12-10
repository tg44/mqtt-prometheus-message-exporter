package xyz.tg44.prometheus

import spray.json._
import xyz.tg44.prometheus.Config.PatternConf
import xyz.tg44.prometheus.exporter.Registry.MetricMeta

import scala.annotation.tailrec
import scala.util.Try

object PatternUtils {
  def isAny(s: String): Boolean = (s == "[[any]]")
  def isPrefix(s: String): Boolean = (s == "[[prefix]]")
  def isPrefixes(s: String): Boolean = (s == "[[prefixes]]")
  def isLabel(s: String): Boolean = (s.startsWith("<<") && s.endsWith(">>"))
  def isSyntax(s: String): Boolean = isAny(s) || isPrefix(s) || isPrefixes(s) || isLabel(s)

  def boolStringTrue(s: String): Boolean = {
    val matchTo = "on" :: "true" :: "y" :: "yes" :: Nil
    matchTo.contains(s.toLowerCase)
  }
  def boolStringFalse(s: String): Boolean = {
    val matchTo = "off" :: "false" :: "n" :: "no" :: Nil
    matchTo.contains(s.toLowerCase)
  }

  def flatten(topic: String, payload: String): List[(String, Double)] = {
    Try(payload.parseJson).toOption match {
      case Some(JsNumber(n)) => (topic -> n.doubleValue) :: Nil
      case Some(JsBoolean(b)) => if(b) (topic -> 1d) :: Nil else (topic -> 0d) :: Nil
      case Some(JsString(s)) if boolStringTrue(s) => (topic -> 1d) :: Nil
      case Some(JsString(s)) if boolStringFalse(s) => (topic -> 0d) :: Nil
      case Some(o: JsObject) => flatten(o, topic)
      case _ => Nil
    }
  }

  def flatten(o: JsObject, acc: String): List[(String, Double)] = {
    o.fields.toList.flatMap{case (k, v) =>
      val topic = s"$acc/$k"
      v match {
        case JsNumber(n) => (topic -> n.doubleValue) :: Nil
        case JsBoolean(b) => if(b) (topic -> 1d) :: Nil else (topic -> 0d) :: Nil
        case JsString(s) if boolStringTrue(s) => (topic -> 1d) :: Nil
        case JsString(s) if boolStringFalse(s) => (topic -> 0d) :: Nil
        case o: JsObject => flatten(o, topic)
        case _ => Nil
      }
    }
  }

  def topicFromPattern(pattern: String): String = {
    val splitted = pattern.split('/')
    if(splitted.contains("|")) {
      splitted
        .takeWhile(_ != "|")
        .map( p =>
          if(isLabel(p) || isPrefix(p) || isAny(p)) {
            "+"
          } else {
            p
          }
        )
        .mkString("/")
    } else {
      if(splitted.length > 1) {
        if(isSyntax(splitted.head)) {
          "#"
        } else {
          splitted.head + "/#"
        }
      } else {
        if(isSyntax(splitted.head)) {
          "+"
        } else {
          splitted.head
        }
      }
    }
  }

  def metaFromPatternAndPath(pattern: String, path: String, prefix: String): Option[MetricMeta] = {
    val patternList = pattern.split('/').filter(_ != "|").toList
    val pathList = path.split('/').toList

    @tailrec
    def rec(patterns: List[String], paths: List[String], metrics: List[String], labels: Map[String, String]): Option[MetricMeta] = {
      patterns match {
        case h :: Nil if paths.nonEmpty && isPrefixes(h) =>
          buildValidMeta(metrics.reverse ++ paths, labels)
        case h :: t =>
          if(isPrefix(h)) {
            rec(t, paths.tail, paths.head :: metrics, labels)
          } else if(isLabel(h)) {
            val label = h.drop(2).dropRight(2)
            rec(t, paths.tail, metrics, labels + (label -> paths.head))
          } else if(isAny(h)) {
            rec(t, paths.tail, metrics, labels)
          } else if(h == paths.head){
            rec(t, paths.tail, metrics, labels)
          } else {
            None
          }
        case Nil if paths.isEmpty =>
          buildValidMeta(metrics.reverse, labels)
        case Nil =>
          None
      }
    }

    if(patternList.size > pathList.size) {
      None
    } else {
      rec(patternList, pathList, prefix :: Nil, Map.empty)
    }
  }

  def metaBuilder(prefixPathList: Seq[PatternConf])(path: String): Option[MetricMeta] = {
    prefixPathList.map(p => metaFromPatternAndPath(p.pattern, _, p.prefix)).foldLeft(Option.empty[MetricMeta])(_ orElse _(path))
  }

  def buildValidMeta(metrics: List[String], labels: Map[String, String]): Option[MetricMeta] = {
    def standardize(s: String): String = {
      s.replace(' ', '_').replace("[^A-Za-z0-9_]", "").trim.toLowerCase
    }
    val metricName =
      metrics
        .map(standardize)
        .filter(_.nonEmpty)
        .mkString("_")
    if(metricName.isEmpty) {
      None
    } else {
      val sL = labels.map{case (k, v) => standardize(k) -> standardize(v)}
      Option(MetricMeta("gauge", metricName, sL, "", None))
    }
  }

}
