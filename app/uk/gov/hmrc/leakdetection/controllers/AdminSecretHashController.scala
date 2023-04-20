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

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import play.api.mvc.ControllerComponents
import play.shaded.oauth.org.apache.commons.codec.binary.Hex
import uk.gov.hmrc.leakdetection.controllers.KnownSecrets.{Algorithm, Encoding, convertKnownSecretsToEncodingHex, validateKnownSecrets}
import uk.gov.hmrc.leakdetection.model.SecretHash
import uk.gov.hmrc.leakdetection.persistence.SecretHashesRepository
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.util.Base64
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AdminSecretHashController @Inject()(
 secretHashesRepository: SecretHashesRepository,
 cc             : ControllerComponents
)(implicit
 ec: ExecutionContext
) extends BackendController(cc) {

  //Our admin controller accepts a JSON response, which it expects to be in the format of a KnownSecrets object.
  //The object will contain a list of SHA256 hashes of secrets (pre-encryption).
  //Each hash in this list can be encoded either in base 64 or Hex, in order to be 'accepted' into our list of known secret hashes.

  def insertKnownSecretHashes = Action.async(parse.json) { implicit request =>
    request.body.validate[KnownSecrets](KnownSecrets.reads).fold(
      err     => Future.successful(BadRequest(err.map { case (path, errors) =>
        path + ": " + errors.mkString
      }.mkString)),
      secrets => {
        validateKnownSecrets(secrets)
          .fold(
            err => Future.successful(BadRequest(err)),
            ks => secretHashesRepository
              .insertHashes(convertKnownSecretsToEncodingHex(ks).hashes.map(SecretHash(_))).map(_ => Ok(""))
          )
      }
    )
  }
}

case class KnownSecrets(
   hashes   : List[String],
   algorithm: Algorithm,
   encoding : Encoding
)

object KnownSecrets {
  sealed trait Algorithm {
    def asString: String
    def hashLength: Int
  }

  case object SHA256 extends Algorithm {
    override def asString: String = "SHA256"

    override def hashLength: Int = 32
  }

  sealed trait Encoding { def asString: String }

  case object EncodingBase64 extends Encoding { override def asString: String = "base64" }
  case object EncodingHex extends Encoding { override def asString: String = "hex" }

  val encodingReads: Reads[Encoding] = new Reads[Encoding] {
    override def reads(json: JsValue): JsResult[Encoding] = {
      json.validate[String].flatMap(s => {
        allowedEncodings.find(_.asString.equalsIgnoreCase(s)) match {
          case None    => JsError("Encoding must be one of: " + allowedEncodings.mkString(","))
          case Some(e) => JsSuccess(e)
        }
      })
    }
  }

  val algorithmReads: Reads[Algorithm] = new Reads[Algorithm] {
    override def reads(json: JsValue): JsResult[Algorithm] = {
      json.validate[String].flatMap(s => {
        allowedAlgorithms.find(_.asString.equalsIgnoreCase(s)) match {
          case None    => JsError("Algorithm must be one of: " + allowedAlgorithms.mkString(","))
          case Some(e) => JsSuccess(e)
        }
      })
    }
  }

  val reads: Reads[KnownSecrets] = {
    ( (__ \ "hashes"     ).read[List[String]]
      ~ (__ \ "algorithm").read[Algorithm](algorithmReads)
      ~ (__ \ "encoding" ).read[Encoding](encodingReads)
      )(KnownSecrets.apply _)
  }

  val allowedAlgorithms: Set[Algorithm] = Set(SHA256)

  val allowedEncodings: Set[Encoding] = Set(EncodingBase64, EncodingHex)

  def validateKnownSecrets(knownSecrets: KnownSecrets): Either[String, KnownSecrets] = knownSecrets match {
    case KnownSecrets(hashes, SHA256, EncodingBase64) => if(hashes.forall(s => Base64.getDecoder.decode(s).length == SHA256.hashLength)) Right(knownSecrets) else Left("Not all hashes were base64 SHA256 encoded.")
    case KnownSecrets(hashes, SHA256, EncodingHex)    => if(hashes.forall(s => Hex.decodeHex(s.toCharArray).length == SHA256.hashLength)) Right(knownSecrets) else Left("Not all hashes were hex SHA256 encoded.")
    case _                                            => Left(s"Unsupported encoding ${knownSecrets.encoding} / algorithm ${knownSecrets.algorithm}")
  }

  def convertKnownSecretsToEncodingHex(knownSecrets: KnownSecrets): KnownSecrets = knownSecrets match {
    case KnownSecrets(_, SHA256, EncodingHex)         => knownSecrets
    case KnownSecrets(hashes, SHA256, EncodingBase64) => KnownSecrets(hashes.map(h => Hex.encodeHex(Base64.getDecoder.decode(h)).mkString), SHA256, EncodingHex)
  }

}

