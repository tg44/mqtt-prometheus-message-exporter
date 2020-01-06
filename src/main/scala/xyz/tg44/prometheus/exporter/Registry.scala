package xyz.tg44.prometheus.exporter

import akka.NotUsed
import akka.actor.ActorSystem
import akka.custom.{HoldWithInitial, PullableSink}
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete}
import xyz.tg44.prometheus.exporter.Registry.{Line, MetricMeta}

class Registry(implicit actorSystem: ActorSystem, mat: Materializer) {

  private[exporter] def offerValue(meta: MetricMeta, value: Double, timeStamp: Option[Long] = None, last: Boolean = false) = {
    pushable.offer(Line(meta, value, timeStamp), last)
  }

  def getState() = {
    pullable.pull()
  }

  private lazy val (pushable, pullable) = merger

  private def merger: (SourceQueueWithComplete[(Line, Boolean)], SinkQueueWithCancel[String]) =
    Source.queue[(Line, Boolean)](1000, OverflowStrategy.dropHead)
      .via(Registry.registryFlow)
      .map(lineMap => PrometheusRenderer.render(lineMap.values))
      .toMat(Sink.fromGraph(new PullableSink[String]()))(Keep.both).run

}

object Registry {

  def registryFlow: Flow[(Line, Boolean), Map[MetricMeta, Line], NotUsed] = {
    def aggregateLine(acc: Map[MetricMeta, Line], l: Line, remove: Boolean = false): Map[MetricMeta, Line] = {
      if (remove) {
        acc.removed(l.primaryKey)
      } else {
        acc.get(l.primaryKey).fold {
          acc + (l.primaryKey -> l)
        } { _ =>
          acc.updated(l.primaryKey, l)
        }
      }
    }

    Flow[(Line, Boolean)]
      .statefulMapConcat { () => {
          //maybe use mutable map instead?
          //with mutable map we still need to add down an immutable representation
          //updating an immutable map probably less painful than creating a new immute structure from the mutable one
          var acc: Map[MetricMeta, Line] = Map.empty
          (data: (Line, Boolean)) => {
            val (l, remove) = data
            acc = aggregateLine(acc, l, remove)
            acc :: Nil
          }
        }
      }
      .via(new HoldWithInitial[Map[MetricMeta, Line]](Map.empty))
  }

  trait Metric {
    def remove(): Unit
  }

  case class MetricMeta(
    mType: String,
    name: String,
    labels: Map[String, String],
    description: String,
    group: Option[String]
  )

  case class Line(
    meta: MetricMeta,
    value: Double,
    timeStamp: Option[Long]
  ) {
    lazy val primaryKey = meta
  }

}

