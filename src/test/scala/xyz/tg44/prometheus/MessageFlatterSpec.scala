package xyz.tg44.prometheus

import org.scalatest.{Matchers, WordSpecLike}
import spray.json._

import scala.util.Try

class MessageFlatterSpec extends WordSpecLike with Matchers {

  import xyz.tg44.prometheus.PatternUtils._

  "flatten" should {

    "filter out not number fields from JsObjects" in {
      val o = """{"num": 5, "arr": [1,2,3], "str": "test", "null": null}""".parseJson.asJsObject
      flatten(o, "") shouldBe List("/num" -> 5)
    }

    "works with bools" when {
      val cases = Seq(
        "\"on\"" -> 1,
        "\"On\"" -> 1,
        "\"ON\"" -> 1,
        "\"true\"" -> 1,
        "\"True\"" -> 1,
        "true" -> 1,
        "\"y\"" -> 1,
        "\"Y\"" -> 1,
        "\"yes\"" -> 1,
        "\"Yes\"" -> 1,
        "\"YES\"" -> 1,
        "\"off\"" -> 0,
        "\"Off\"" -> 0,
        "\"OFF\"" -> 0,
        "\"false\"" -> 0,
        "\"False\"" -> 0,
        "\"FALSE\"" -> 0,
        "false" -> 0,
        "\"n\"" -> 0,
        "\"N\"" -> 0,
        "\"no\"" -> 0,
        "\"NO\"" -> 0,
      )
      cases.foreach{ case (value, res) =>
        s"$value -> $res" in {
          val o = s"""{"active": $value}""".parseJson.asJsObject
          flatten(o, "") shouldBe List("/active" -> res)
        }
      }
    }

    "works with multi level JsObjects" in {
      val o1 = """{"num": 5, "arr": [1,2,3], "str": "test", "null": null}"""
      val o = s"""{"num": 10, "inner": $o1, "so": {"deep": {"really": {"really": 8}}}}""".parseJson.asJsObject
      flatten(o, "").sortBy(_._2) shouldBe List("/num" -> 10, "/inner/num" -> 5, "/so/deep/really/really" -> 8).sortBy(_._2)
    }

    "works with topics and numbers" in {
      flatten("topic", "9") shouldBe List("topic" -> 9)
    }

    "works with topics too" in {
      val o = """{"num": 5, "arr": [1,2,3], "str": "test", "null": null}"""
      flatten("topic", o) shouldBe List("topic/num" -> 5)
    }

  }
}
