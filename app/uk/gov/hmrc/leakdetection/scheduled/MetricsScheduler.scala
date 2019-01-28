/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection.scheduled

import javax.inject.Inject

import akka.actor.ActorSystem
import com.kenshoo.play.metrics.Metrics
import play.api.{Configuration, Logger}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.DefaultDB
import uk.gov.hmrc.leakdetection.persistence.GithubRequestsQueueRepository
import uk.gov.hmrc.leakdetection.services.ReportsService
import uk.gov.hmrc.lock.{ExclusiveTimePeriodLock, LockRepository}
import uk.gov.hmrc.metrix.MetricOrchestrator
import uk.gov.hmrc.metrix.persistence.MongoMetricRepository

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class MetricsScheduler @Inject()(
  actorSystem: ActorSystem,
  configuration: Configuration,
  metrics: Metrics,
  reactiveMongoComponent: ReactiveMongoComponent,
  githubRequestsQueueRepository: GithubRequestsQueueRepository,
  reportsService: ReportsService)(implicit ec: ExecutionContext) {
  private val key = "queue.metricsGauges.interval"
  lazy val refreshIntervalMillis: Long =
    configuration.getMilliseconds(key).getOrElse(throw new RuntimeException(s"$key not specified"))
  implicit lazy val mongo: () => DefaultDB = reactiveMongoComponent.mongoConnector.db
  val lock = new ExclusiveTimePeriodLock {
    override val repo: LockRepository = new LockRepository()
    override val lockId: String       = "queue"
    override val holdLockFor          = new org.joda.time.Duration(refreshIntervalMillis)

  }
  val metricOrchestrator = new MetricOrchestrator(
    metricSources    = List(githubRequestsQueueRepository, reportsService),
    lock             = lock,
    metricRepository = new MongoMetricRepository(),
    metricRegistry   = metrics.defaultRegistry
  )
  actorSystem.scheduler.schedule(1.minute, refreshIntervalMillis.milliseconds) {
    metricOrchestrator
      .attemptToUpdateAndRefreshMetrics()
      .map(_.andLogTheResult())
      .recover({
        case e: RuntimeException =>
          Logger.error(s"An error occurred processing metrics: ${e.getMessage}", e)
      })
  }
}
