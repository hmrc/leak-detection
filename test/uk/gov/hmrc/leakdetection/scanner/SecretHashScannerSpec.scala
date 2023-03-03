package uk.gov.hmrc.leakdetection.scanner

import org.apache.commons.codec.binary.Hex
import org.scalacheck.Prop.True
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import uk.gov.hmrc.leakdetection.config.SecretHashConfig

class SecretHashScannerSpec extends AnyWordSpec with Matchers {

  //Mock unhashed decrypted secrets
  val knownSecrets = Set("HELLOWORLD", "TESTSECRET")

  val knownHashes = Set(
    "0b21b7db59cd154904fac6336fa7d2be1bab38d632794f281549584068cdcb74",
    "f6d81236a7a7717a1fe6bf50a69d8fe72cb0501836e516d59e311304859e696a"
  )

  "shouldScan" should {
    val hashScanner = new SecretHashScanner(SecretHashConfig(15), knownHashes)
    "return true if the word is greater than the min word length" in {
      hashScanner.shouldScan("1234567890123456") shouldBe true
    }

    "return true if the word is equal to the min word length" in {
      hashScanner.shouldScan("123456789012345") shouldBe true
    }

    "return false if the word is less than the min word length" in {
      hashScanner.shouldScan("12345678901234") shouldBe false
    }
  }

  "splitLine" should {
    val hashScanner = new SecretHashScanner(SecretHashConfig(15), knownHashes)
    "split words by a single whitespace" in {
      hashScanner.splitLine("Here are some words") should contain theSameElementsInOrderAs  Seq("Here", "are", "some", "words")
    }

    "split words seperated by varying amounts of whitespace" in {
      hashScanner.splitLine("Here  are\tsome   words") should contain theSameElementsInOrderAs  Seq("Here", "are", "some", "words")
    }

    "return an empty sequence when given a blank string" in {
      hashScanner.splitLine(" ") shouldBe empty
    }

    "return an empty sequence when given an empty string" in {
      hashScanner.splitLine("") shouldBe empty
    }
  }

  "hash" should {
    val hashScanner = new SecretHashScanner(SecretHashConfig(15), knownHashes)
    "create a SHA256 hash of a given string" in {
      hashScanner.hash("HELLOWORLD") should contain theSameElementsInOrderAs Hex.decodeHex("0b21b7db59cd154904fac6336fa7d2be1bab38d632794f281549584068cdcb74")
    }
  }

  "scanLine" should {
    val hashScanner = new SecretHashScanner(SecretHashConfig(5), knownHashes)
    "return a matched result if the line contains a known secret" in {
      val res = hashScanner.scanLine("THIS CONTAINS A SECRET HELLOWORLD GOODBYE", 3, "/hello.txt", false, Seq.empty)
      res.nonEmpty shouldBe true
    }

    "return none if the line does not contain a known secret" in {
      val res = hashScanner.scanLine("THIS CONTAINS NO SECRET", 3, "/hello.txt", false, Seq.empty)
      res.nonEmpty shouldBe false
    }

    "return a MatchedResult with the lineText attribute containing the redacted secret" in {
      val res = hashScanner.scanLine("THIS CONTAINS A SECRET HELLOWORLD GOODBYE", 3, "/hello.txt", false, Seq.empty).get
      res.lineText shouldBe "THIS CONTAINS A SECRET ********** GOODBYE"
    }

    "return a MatchedResult with the start and end index of the secret identified" in {
      val res = hashScanner.scanLine("THIS CONTAINS A SECRET HELLOWORLD GOODBYE", 3, "/hello.txt", false, Seq.empty).get
      res.matches.head shouldBe Match(23,33)
    }
  }
}
