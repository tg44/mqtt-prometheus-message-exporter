package xyz.tg44.prometheus.exporter

import java.util.concurrent.TimeUnit

import akka.actor.typed.ActorRef
import akka.util.Timeout
import cats.{Functor, Id}
import cats.effect.Clock
import akka.{actor => classic}
import xyz.tg44.prometheus.exporter.Registry.{Metric, MetricMeta}
import xyz.tg44.prometheus.exporter.actors.{CKMSQuantileActor, CounterActor, GaugeActor, LabeledMetricActor}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

class Summary private[exporter] (
  buckets: ActorRef[CKMSQuantileActor.Command],
  sum: ActorRef[GaugeActor.Command],
  count: ActorRef[CounterActor.Command]
) extends Metric {
  def observe(amount: Double)(implicit clock: Clock[Id]): Unit = {
    buckets ! CKMSQuantileActor.Observe(amount)
    count ! CounterActor.Inc()
    sum ! GaugeActor.ModifyWith(amount, clock.realTime(TimeUnit.MILLISECONDS))
  }

  override def remove(): Unit = {
    sum ! GaugeActor.Stop()
    count ! CounterActor.Stop()
    buckets ! CKMSQuantileActor.Stop()
  }
}

object Summary {
  val mType = "summary"

  trait implicits {
    implicit class SummarymFHelper[F[_]](c: F[Summary]) {
      import cats.implicits._
      def observe(amount: Double = 1)(implicit ev: Functor[F], clock: Clock[Id]): Unit = {
        c.map(_.observe(amount))
      }

      def remove()(implicit ev: Functor[F]): Unit = {
        c.map(_.remove())
      }
    }
  }

  def apply(name: String, description: String, quantiles: Seq[Quantile], rotationTime: FiniteDuration, numberOfBuckets: Int)(implicit clock: Clock[Id], to: Timeout, ec: ExecutionContext, as: classic.ActorSystem, registry: Registry): Summary = {
    val meta = MetricMeta(mType, name, Map.empty, description, Some(name))
    Summary(meta, quantiles, rotationTime, numberOfBuckets)
  }

  def withLabels[L <: Enumeration](name: String, description: String, quantiles: Seq[Quantile], rotationTime: FiniteDuration, numberOfBuckets: Int)(implicit clock: Clock[Id], to: Timeout, ec: ExecutionContext, as: classic.ActorSystem, registry: Registry): LabeledMetric[Summary, L] = {
    import akka.actor.typed.scaladsl.adapter._
    new LabeledMetric[Summary, L](as.spawn(
      LabeledMetricActor[Summary](name, description, Histogram.mType)(mm => Summary(mm, quantiles, rotationTime, numberOfBuckets)
      ), name))
  }

  private def apply(meta: MetricMeta, quantiles: Seq[Quantile], rotationTime: FiniteDuration, numberOfBuckets: Int)(implicit clock: Clock[Id], to: Timeout, ec: ExecutionContext, as: classic.ActorSystem, registry: Registry): Summary = {
    import akka.actor.typed.scaladsl.adapter._
    val sum = as.spawnAnonymous(actors.GaugeActor(meta.copy(name = s"${meta.name}_sum"), registry))
    val counter = as.spawnAnonymous(actors.CounterActor(meta.copy(name = s"${meta.name}_count"), registry))
    val buckets = as.spawnAnonymous(actors.CKMSQuantileActor(meta, quantiles, rotationTime, numberOfBuckets, registry))
    new Summary(buckets, sum, counter)
  }
}
