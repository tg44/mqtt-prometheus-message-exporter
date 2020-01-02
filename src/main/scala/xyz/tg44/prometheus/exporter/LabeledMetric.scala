package xyz.tg44.prometheus.exporter

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import akka.{actor => classic}
import xyz.tg44.prometheus.exporter.actors.LabeledMetricActor._
import xyz.tg44.prometheus.exporter.Registry.Metric

import scala.concurrent.{ExecutionContext, Future}

class LabeledMetric[M <: Metric, L <: Enumeration] private[exporter] (actor: ActorRef[Command])(implicit ec: ExecutionContext, as: classic.ActorSystem) extends Metric {
  import akka.actor.typed.scaladsl.adapter._
  implicit val typedSystem = as.toTyped

  def get(selector: Map[L#Value, String])(implicit to: Timeout): Future[M] = {
    actor.ask((ar: ActorRef[Response[M]]) => Select[M](transformSelector(selector), ar)).map(_.m)
  }

  def remove(selector: Map[L#Value, String]): Unit = {
    actor ! Remove(transformSelector(selector))
  }

  def clear(): Unit = {
    actor ! RemoveAll()
  }

  private def transformSelector(selector: Map[L#Value, String]): Map[String, String] = {
    selector.map{case (k, v) => k.toString -> v}
  }

  override def remove(): Unit = {
    actor ! Stop()
  }
}
