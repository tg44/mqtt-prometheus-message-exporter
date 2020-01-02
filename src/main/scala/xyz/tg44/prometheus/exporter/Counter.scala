package xyz.tg44.prometheus.exporter

import akka.actor.typed.ActorRef
import akka.actor.ActorSystem
import cats.{Functor, Id}
import xyz.tg44.prometheus.exporter.actors.CounterActor.{Command, Inc, Stop}
import xyz.tg44.prometheus.exporter.Registry.{Metric, MetricMeta}
import xyz.tg44.prometheus.exporter.actors.{CounterActor, LabeledMetricActor}

import scala.concurrent.ExecutionContext

class Counter private[exporter] (actor: ActorRef[Command]) extends Metric {
  def inc(amount: Double = 1): Unit = {
    actor ! Inc(amount)
  }

  override def remove(): Unit = {
    actor ! Stop()
  }
}

object Counter {
  val mType = "counter"

  trait implicits {
    implicit class CounterFHelper[F[_]](c: F[Counter]) {
      import cats.implicits._
      def inc(amount: Double = 1)(implicit ev: Functor[F]): Unit = {
        c.map(_.inc(amount))
      }

      def remove()(implicit ev: Functor[F]): Unit = {
        c.map(_.remove())
      }
    }
  }

  def apply(name: String, description: String)(implicit as: ActorSystem, registry: Registry): Counter = {
    import akka.actor.typed.scaladsl.adapter._
    val meta = MetricMeta(mType, name, Map.empty[String, String], description, None)
    new Counter(as.spawn(CounterActor(meta, registry), name))
  }

  def withLabels[L <: Enumeration](name: String, description: String)(implicit as: ActorSystem, registry: Registry, executionContext: ExecutionContext): LabeledMetric[Counter, L] = {
    import akka.actor.typed.scaladsl.adapter._
    new LabeledMetric[Counter, L](as.spawn(
      LabeledMetricActor(name, description, mType)(mm => new Counter(as.spawnAnonymous(actors.CounterActor(mm, registry)))
    ), name))
  }

}
