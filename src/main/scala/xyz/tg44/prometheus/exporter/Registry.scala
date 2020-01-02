package xyz.tg44.prometheus.exporter

import akka.actor.ActorSystem
import akka.custom.{HoldWithInitial, PullableSink}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete}
import xyz.tg44.prometheus.exporter.Registry.{Line, MetricMeta}

class Registry(implicit actorSystem: ActorSystem) {

  private[exporter] def offerValue(meta: MetricMeta, value: Double, timeStamp: Option[Long] = None, last: Boolean = false) = {
    pushable.offer(Line(meta, value, timeStamp), last)
  }

  def getState() = {
    pullable.pull()
  }

  private lazy val (pushable, pullable) = merger

  private def merger: (SourceQueueWithComplete[(Line, Boolean)], SinkQueueWithCancel[String]) =
    Source.queue[(Line, Boolean)](1000, OverflowStrategy.dropHead)
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
      .map(lineMap => render(lineMap.values))
      .toMat(Sink.fromGraph(new PullableSink[String]()))(Keep.both).run

  private def aggregateLine(acc: Map[MetricMeta, Line], l: Line, remove: Boolean = false): Map[MetricMeta, Line] = {
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


  private def render(lines: Iterable[Line]): String = {
    //TODO missing escaping and label/name checks
    def renderLabels(labels: Seq[(String, String)]) = {
      if(labels.nonEmpty) {
        labels.map { case (k, v) => s"""$k="$v"""" }.mkString("{", ",", "}")
      } else {
        ""
      }
    }

    def renderLine(t: Line): String = {
      s"${t.meta.name}${renderLabels(t.meta.labels.toSeq)} ${t.value}${t.timeStamp.map(ts => s" $ts").getOrElse("")}"
    }

    lines.groupBy(_.meta.group).toSeq.flatMap {
      case (Some(g), vl: Iterable[Line]) =>
        vl.headOption.fold{Seq.empty[String]}{ head =>
          val mt = head.meta.mType
          val description = head.meta.description
          Seq(
            s"# HELP $g $description",
            s"# TYPE $g $mt",
          ) ++ vl.map(renderLine)
        }
      case (None, vl: Seq[Line]) if vl.nonEmpty =>
        vl.flatMap(t =>
          Seq(
            s"# HELP ${t.meta.name} ${t.meta.description}",
            s"# TYPE ${t.meta.name} ${t.meta.mType}",
            renderLine(t)
          )
        )
      case _ => Seq.empty[String]
    }.mkString("\n")
  }

}

object Registry {

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

