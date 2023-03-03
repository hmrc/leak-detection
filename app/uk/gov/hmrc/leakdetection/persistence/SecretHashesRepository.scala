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

package uk.gov.hmrc.leakdetection.persistence

import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import uk.gov.hmrc.leakdetection.model.SecretHash

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import scala.collection.View.Filter


@Singleton
class SecretHashesRepository @Inject()(
                                   mongoComponent: MongoComponent
                                 )(implicit ec: ExecutionContext
                                 ) extends PlayMongoRepository[SecretHash](
  collectionName = "reports",
  mongoComponent = mongoComponent,
  domainFormat   = SecretHash.mongoFormat,
  indexes        = Seq(
    IndexModel(Indexes.hashed("repoName"), IndexOptions().name("repoName-idx").background(true)),
  )
) {

  def insertHashes(hashes: Seq[SecretHash]): Future[Unit] =
    collection.insertMany(hashes).toFuture().map(_ => ())

  def isKnownSecretHash(hash: String): Future[Boolean] =
    collection.find(
      filter =  Filters.equal("hash", hash)
    ).headOption().map(_.nonEmpty)

  def isKnownSecretHash(hashes: Seq[String]): Future[List[String]] =
    collection.find(
      filter = Filters.in("hash", hashes)
    ).map(_.hash)
      .toFuture()
      .map(_.toList)


}

