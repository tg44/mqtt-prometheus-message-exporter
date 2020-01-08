package xyz.tg44.prometheus

import org.scalatest.{Matchers, WordSpecLike}
import xyz.tg44.prometheus.exporter.Gauge
import xyz.tg44.prometheus.exporter.Registry.MetricMeta

class TopicParserSpec extends WordSpecLike with Matchers {

  import xyz.tg44.prometheus.PatternUtils._

  case class TestCaseHelper(
    name: String,
    pattern: String,
    topic: String,
    inputPath: String,
    prefix: String,
    metricMeta: Option[MetricMeta]
  )

  def createMetricMetaForTest(name: String, labels: Map[String, String]) = {
    MetricMeta(Gauge.mType, name, labels, "", None)
  }

  val singlePart = TestCaseHelper("singlePart", "topic", "topic", "topic", "", None)
  val overdefinedWithoutPrefix = TestCaseHelper("overdefinedWithoutPrefix", "topic/metric", "topic/#", "topic/metric", "", None)
  val overdefinedWithPrefix = TestCaseHelper("overdefinedWithPrefix", "topic/metric", "topic/#", "topic/metric", "pf", Some(createMetricMetaForTest("pf", Map.empty)))
  val nonMatchingPath1 = TestCaseHelper("nonMatchingPath1", "topic/metric", "topic/#", "topic/metricc", "pf", None)
  val nonMatchingPath2 = TestCaseHelper("nonMatchingPath2", "topic/metric", "topic/#", "t", "pf", None)
  val singlePrefix = TestCaseHelper("singlePrefix", "topic/[[prefix]]", "topic/#", "topic/metric", "", Some(createMetricMetaForTest("metric", Map.empty)))
  val singlePrefixWithDefaultPrefix = TestCaseHelper("singlePrefixWithDefaultPrefix", "topic/[[prefix]]", "topic/#", "topic/metric", "pf", Some(createMetricMetaForTest("pf_metric", Map.empty)))
  val singleLableAndPrefixNonMatching = TestCaseHelper("singleLableAndPrefixNonMatching", "topic/<<device>>/[[prefix]]", "topic/#", "topic/metric", "pf", None)
  val singleLableAndPrefix1 = TestCaseHelper("singleLableAndPrefix1", "topic/<<device>>/[[prefix]]", "topic/#", "topic/test/STAT", "", Some(createMetricMetaForTest("stat", Map("device" -> "test"))))
  val singleLableAndPrefix2 = TestCaseHelper("singleLableAndPrefix2", "topic/<<owner>>/[[prefix]]", "topic/#", "topic/test/STAT", "", Some(createMetricMetaForTest("stat", Map("owner" -> "test"))))
  val multiPrefix1 = TestCaseHelper("multiPrefix1", "topic/[[prefixes]]", "topic/#", "topic/test/STAT", "", Some(createMetricMetaForTest("test_stat", Map.empty)))
  val multiPrefix2 = TestCaseHelper("multiPrefix2", "topic/[[prefixes]]", "topic/#", "topic/test/STAT/other", "", Some(createMetricMetaForTest("test_stat_other", Map.empty)))
  val topicOptimization = TestCaseHelper("topicOptimization", "topic/other/|/stat", "topic/other", "topic/other/stat", "pf", Some(createMetricMetaForTest("pf", Map.empty)))
  val topicOptimizationNonMatching = TestCaseHelper("topicOptimizationNonMatching", "topic/other/|/stat", "topic/other", "topic/other/stat2", "pf", None)
  val topicOptimizationWithLabel = TestCaseHelper("topicOptimizationWithLabel", "topic/<<device>>/stat/|/stat", "topic/+/stat", "topic/other/stat/stat", "pf", Some(createMetricMetaForTest("pf", Map("device" -> "other"))))
  val topicOptimizationMultipleWithLabel = TestCaseHelper("topicOptimizationMultipleWithLabel", "topic/<<device>>/<<owner>>/|/stat", "topic/+/+", "topic/other/stat/stat", "pf", Some(createMetricMetaForTest("pf", Map("device" -> "other", "owner" -> "stat"))))


  val testCases = Seq(
    singlePart,
    overdefinedWithoutPrefix,
    overdefinedWithPrefix,
    nonMatchingPath1,
    nonMatchingPath2,
    singlePrefix,
    singlePrefixWithDefaultPrefix,
    singleLableAndPrefixNonMatching,
    singleLableAndPrefix1,
    singleLableAndPrefix2,
    multiPrefix1,
    multiPrefix2,
    topicOptimization,
    topicOptimizationNonMatching,
    topicOptimizationWithLabel,
    topicOptimizationMultipleWithLabel,
  )
  "topic parsing" should {
    testCases.foreach { c =>
      s"${c.name} - pattern: ${c.pattern}" in {
        topicFromPattern(c.pattern) shouldBe c.topic
      }
    }
  }

  "input path parsing" should {
    testCases.foreach { c =>
      s"${c.name} - pattern: ${c.pattern}" in {
        metaFromPatternAndPath(c.pattern, c.inputPath, c.prefix) shouldBe c.metricMeta
      }
    }
  }
}
