package xyz.tg44.prometheus.exporter

import akka.actor.typed.ActorRef
import akka.actor.ActorSystem
import akka.util.Timeout
import cats.Id
import cats.effect.Clock
import xyz.tg44.prometheus.exporter.actors.LabeledMetricActor._
import xyz.tg44.prometheus.exporter.Registry.Metric
import xyz.tg44.prometheus.exporter.actors.{GaugeActor, LabeledMetricActor}

import scala.concurrent.ExecutionContext

class Info private[exporter] (lm: ActorRef[Command]) extends Metric {
  override def remove(): Unit = {
    lm ! Stop()
  }
}

object Info {
  def apply(name: String, description: String, info: Map[String,String])(implicit clock: Clock[Id], as: ActorSystem, registry: Registry, executionContext: ExecutionContext, to: Timeout): Info = {
    import akka.actor.typed.scaladsl.adapter._
    import akka.actor.typed.scaladsl.AskPattern._
    implicit val typedSystem = as.toTyped
    val actor = as.spawnAnonymous(LabeledMetricActor(name, description, Gauge.mType)(mm => new Gauge(as.spawnAnonymous(GaugeActor(mm, registry)))))
    actor.ask((ar: ActorRef[Response[Gauge]]) => Select[Gauge](info, ar)).map(_.m.set(1))
    new Info(actor)
  }
}
