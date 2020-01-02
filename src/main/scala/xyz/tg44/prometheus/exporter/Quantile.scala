package xyz.tg44.prometheus.exporter

case class Quantile(quantile: Double, error: Double) {
  require(quantile > 0 && quantile < 1, "Quantile should be 0 < q < 1 !")
  require(error > 0 && error < 1, "Error should be 0 < e < 1 !")
  val u: Double = 2.0 * error / (1.0 - quantile)
  val v: Double = 2.0 * error / quantile
}
