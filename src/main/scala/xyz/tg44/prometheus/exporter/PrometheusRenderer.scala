package xyz.tg44.prometheus.exporter

import xyz.tg44.prometheus.exporter.Registry.Line

object PrometheusRenderer {

  def render(lines: Iterable[Line]): String = {
    def renderLabels(labels: Seq[(String, String)]) = {
      val regexp = "[a-zA-Z_][a-zA-Z0-9_]*" // from https://prometheus.io/docs/concepts/data_model/#metric-names-and-labels
      if(labels.nonEmpty) {
        labels.map { case (k, v) =>
          val value = v.replace("\"", "")
          if(k.matches(regexp)) {
            s"""$k="$value""""
          } else {
            val key = "_x_" + k.replaceAll("[^a-zA-Z0-9_]+", "_")
            s"""$key="$value""""
          }
        }.mkString("{", ",", "}")
      } else {
        ""
      }
    }

    def renderLine(t: Line): String = {
      s"${fixName(t.meta.name)}${renderLabels(t.meta.labels.toSeq)} ${t.value}${t.timeStamp.map(ts => s" $ts").getOrElse("")}"
    }

    def fixName(name: String) = {
      val regexp = "[a-zA-Z_:][a-zA-Z0-9_:]*" // from https://prometheus.io/docs/concepts/data_model/#metric-names-and-labels
      if(name.matches(regexp)) {
        name
      } else {
        "_x_" + name.replaceAll("[^a-zA-Z0-9_:]+", "_")
      }
    }

    lines.groupBy(_.meta.group).toSeq.flatMap {
      case (Some(g), vl: Iterable[Line]) =>
        vl.headOption.fold{Seq.empty[String]}{ head =>
          val mt = head.meta.mType
          val description = head.meta.description
          Seq(
            s"# HELP ${fixName(g)} $description",
            s"# TYPE ${fixName(g)} $mt",
          ) ++ vl.map(renderLine)
        }
      case (None, vl: Seq[Line]) if vl.nonEmpty =>
        vl.flatMap(t =>
          Seq(
            s"# HELP ${fixName(t.meta.name)} ${t.meta.description}",
            s"# TYPE ${fixName(t.meta.name)} ${t.meta.mType}",
            renderLine(t)
          )
        )
      case _ => Seq.empty[String]
    }.mkString("\n")
  }
}
