package me.cference.iris.domain

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets

class ContentHashSpec extends AnyWordSpec with Matchers with EitherValues:

  "ContentHash" should {

    "match the known SHA-256 of a fixed input" in {
      // sha256("abc") — the classic test vector.
      ContentHash.ofBytes("abc".getBytes(StandardCharsets.UTF_8)).value shouldBe
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    "round-trip through parse" in {
      val h = ContentHash.ofBytes(Array[Byte](1, 2, 3))
      ContentHash.parse(h.value).value shouldBe h
    }

    "reject wrong length and non-hex" in {
      ContentHash.parse("abc").isLeft shouldBe true
      ContentHash.parse("G" * 64).isLeft shouldBe true
      ContentHash.parse("A" * 64).isLeft shouldBe true // uppercase is not canonical
    }
  }
