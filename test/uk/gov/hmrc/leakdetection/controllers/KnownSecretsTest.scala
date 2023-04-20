/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.leakdetection.controllers

import play.shaded.oauth.org.apache.commons.codec.DecoderException
import org.scalatest.EitherValues
import org.scalatest.matchers.must.Matchers.{a, contain, convertToAnyMustWrapper, include}
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsResultException, Json}
import uk.gov.hmrc.leakdetection.controllers.KnownSecrets.{EncodingBase64, EncodingHex, SHA256}

import scala.language.postfixOps

class KnownSecretsTest extends AnyWordSpec with EitherValues {

  val secret             = "hello"
  val secretSHA256Hex    = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
  val secretSHA256Base64 = "LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ="
  val secretSHA1Hex      = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"
  val secretSHA256Base32 = "FTZE3OS7WCRQ4JXIHMVMLOPCTYNRMHS4D6TUEXTTAQZWFE4LTASA===="
  val secretSHA1Base64   = "YWFmNGM2MWRkY2M1ZThhMmRhYmVkZTBmM2I0ODJjZDlhZWE5NDM0ZA=="


  "KnownSecretsTest.reads" when {
    val knownSecretsHex = s"""{
                            |    "hashes"   : ["${secretSHA256Hex}"],
                            |    "algorithm": "SHA256",
                            |    "encoding" : "hex"
                            |}""".stripMargin

    val knownSecretsB64 = s"""{
                            |    "hashes"   : ["${secretSHA256Base64}"],
                            |    "algorithm": "SHA256",
                            |    "encoding" : "base64"
                            |}""".stripMargin

    val knownSecretsSHA1 = s"""{
                             |    "hashes"   : ["${secretSHA1Hex}"],
                             |    "algorithm": "SHA1",
                             |    "encoding" : "hex"
                             |}""".stripMargin

    val knownSecretsB32 = s"""{
                            |    "hashes"   : ["${secretSHA256Base32}"],
                            |    "algorithm": "SHA256",
                            |    "encoding" : "base32"
                            |}""".stripMargin


    implicit val ks = KnownSecrets.reads
    "parsing a json representation of knownSecrets with SHA256 hashing & hex encoding" should {
    "return a KnownSecrets object with the expected fields" in {
      val res = Json.parse(knownSecretsHex).as[KnownSecrets]
      res.hashes    mustBe List(secretSHA256Hex)
      res.algorithm mustBe SHA256
      res.encoding  mustBe EncodingHex
    }
   }

    "parsing a json representation of knownSecrets with SHA256 hashing & b64 encoding" should {
      "return a KnownSecrets object with the expected fields" in {
        val res = Json.parse(knownSecretsB64).as[KnownSecrets]
        res.hashes    mustBe List(secretSHA256Base64)
        res.algorithm mustBe SHA256
        res.encoding  mustBe EncodingBase64
      }
    }

    "parsing a json representation of knownSecrets with an unsupported hashing algorithm" should {
      "throw appropriate algorithm JSResultException" in {
        val caught = intercept[JsResultException] {
          Json.parse(knownSecretsSHA1).as[KnownSecrets]
        }

        caught.getMessage must include (s"Algorithm must be one of: ${KnownSecrets.allowedAlgorithms.mkString(",")}")
      }
    }

    "parsing a json representation of knownSecrets with an unsupported encoding method" should {
      "throw appropriate encoding JSResultException" in {
        val caught = intercept[JsResultException] {
          Json.parse(knownSecretsB32).as[KnownSecrets]
        }

        caught.getMessage must include (s"Encoding must be one of: ${KnownSecrets.allowedEncodings.mkString(",")}")
      }
    }
  }

  "validateKnownSecrets" when {
    val unsupportedAlgoHex = KnownSecrets(hashes = List(secretSHA1Hex), algorithm = SHA256 , encoding = EncodingHex)
    val unsupportedAlgoB64 = KnownSecrets(hashes = List(secretSHA1Base64), algorithm = SHA256 , encoding = EncodingBase64)
    val unsupported1Base32  = KnownSecrets(hashes = List(secretSHA256Base32), algorithm = SHA256, encoding = EncodingBase64)
    val unsupported2Base32  = KnownSecrets(hashes = List(secretSHA256Base32), algorithm = SHA256, encoding = EncodingHex)

    val base64Encoded = KnownSecrets(hashes = List(secretSHA256Base64), algorithm = SHA256, encoding = EncodingBase64)
    val hexEncoded = KnownSecrets(hashes = List(secretSHA256Hex), algorithm = SHA256, encoding = EncodingHex)



    "given a KnownSecrets, encoding type hex, with an algorithm incorrectly stated as SHA256" should {
      "return a left stating that it is unsupported" in {
        val res = KnownSecrets.validateKnownSecrets(unsupportedAlgoHex)
        res.isLeft mustBe true
        res.left.value mustBe "Not all hashes were hex SHA256 encoded."
      }
    }

    "given a KnownSecrets, encoding type base64, with an algorithm incorrectly stated as SHA256" should {
      "return a left stating that it is unsupported" in {
        val res = KnownSecrets.validateKnownSecrets(unsupportedAlgoB64)
        res.isLeft mustBe true
        res.left.value mustBe "Not all hashes were base64 SHA256 encoded."

      }
    }

    "given a KnownSecrets, algorithm SHA256, with encoding incorrectly stated as Base64" should {
      "return an IllegalArgumentException during the decoding process" in {
        val caught = intercept[IllegalArgumentException] {
          KnownSecrets.validateKnownSecrets(unsupported1Base32)
        }

        caught.getMessage must include ("Input byte array has wrong 4-byte ending unit")
      }
    }

    "given a KnownSecrets, algorithm SHA256, with encoding incorrectly stated as Hex" should {
      "return a DecoderException during the decoding process" in {
        val caught = intercept[DecoderException] {
          KnownSecrets.validateKnownSecrets(unsupported2Base32)
        }

        caught.getMessage must include ("Illegal hexadecimal charcter")
      }
    }

    "given a KnownSecrets, algorithm SHA256, Base64 encoded" should {
      "return the same known secrets object inside a Right" in {
        val res = KnownSecrets.validateKnownSecrets(base64Encoded)
        res.isRight mustBe true
        res.right.value mustBe base64Encoded
      }
    }

    "given a KnownSecrets, algorithm SHA256, Hex encoded" should {
      "return the same known secrets object inside a Right" in {
        val res = KnownSecrets.validateKnownSecrets(hexEncoded)
        res.isRight mustBe true
        res.right.value mustBe hexEncoded

      }
    }
  }

  "convertKnownSecretsToEncodingHex" when {
    val base64Encoded = KnownSecrets(hashes = List(secretSHA256Base64, secretSHA256Base64), algorithm = SHA256, encoding = EncodingBase64)
    val hexEncoded = KnownSecrets(hashes = List(secretSHA256Hex, secretSHA256Hex), algorithm = SHA256, encoding = EncodingHex)
    "given a KnownSecrets of SHA256/Hex" should {
      "return the same KnownSecrets object" in {
        val res = KnownSecrets.convertKnownSecretsToEncodingHex(hexEncoded)
        res mustBe hexEncoded
      }
    }

    "given a KnownSecrets of SHA256/Base64" should {
      "return a new KnownSecrets object,converting the KnownSecrets hashes to Hex, and changing the encoding type to Hex" in {
        val res = KnownSecrets.convertKnownSecretsToEncodingHex(base64Encoded)
        res.hashes must contain theSameElementsAs(hexEncoded.hashes)
        res.algorithm mustBe SHA256
        res.encoding mustBe EncodingHex
      }
    }
  }
}
