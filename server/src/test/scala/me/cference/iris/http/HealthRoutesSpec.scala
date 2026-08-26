package me.cference.iris.http

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class HealthRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  "GET /health" should {

    "report UP with the version while ready" in {
      Get("/health") ~> HealthRoutes("1.2.3", () => true) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe """{"status":"UP","service":"iris","version":"1.2.3"}"""
      }
    }

    "report DOWN once readiness is withdrawn" in {
      Get("/health") ~> HealthRoutes("1.2.3", () => false) ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
        responseAs[String] should include(""""status":"DOWN"""")
      }
    }
  }
