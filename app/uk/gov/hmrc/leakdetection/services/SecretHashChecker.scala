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

package uk.gov.hmrc.leakdetection.services

import uk.gov.hmrc.leakdetection.persistence.SecretHashesRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

trait SecretHashChecker {
  def check(hashes: List[String]): Future[List[String]]
}

class InMemorySecretHashChecker(knownHashes: Set[String]) extends SecretHashChecker {
  override def check(hashes: List[String]): Future[List[String]] =
    {
      Future.successful(hashes.filter(h => knownHashes.contains(h)))
    }
}

@Singleton
class MongoSecretHashChecker @Inject() (secretHashesRepository: SecretHashesRepository) extends SecretHashChecker {
  override def check(hashes: List[String]): Future[List[String]] = secretHashesRepository.isKnownSecretHash(hashes)
}
