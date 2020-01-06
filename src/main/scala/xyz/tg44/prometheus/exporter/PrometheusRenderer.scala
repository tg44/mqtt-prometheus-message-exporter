package xyz.tg44.prometheus.exporter

import xyz.tg44.prometheus.exporter.Registry.Line

object PrometheusRenderer {

  def render(lines: Iterable[Line]): String = {
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
