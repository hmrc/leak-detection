/*
 * Copyright 2021 HM Revenue & Customs
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
import uk.gov.hmrc.leakdetection.persistence.GithubRequestsQueueRepository
import uk.gov.hmrc.leakdetection.services.ReportsService
import uk.gov.hmrc.mongo.lock.{LockRepository, MongoLockService}
import uk.gov.hmrc.mongo.metrix.{MetricOrchestrator, MetricRepository}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class MetricsScheduler @Inject()(
  actorSystem: ActorSystem,
  configuration: Configuration,
  metrics: Metrics,
  githubRequestsQueueRepository: GithubRequestsQueueRepository,
  reportsService: ReportsService,
  lockRepository: LockRepository,
  metricRepository: MetricRepository
)(implicit ec: ExecutionContext) {

  private val key = "queue.metricsGauges.interval"

  lazy val refreshIntervalMillis: Long = configuration.getMillis(key)

  val logger = Logger(this.getClass.getName)

  val lock = MongoLockService(
    lockRepository = lockRepository,
    lockId         = "queue",
    ttl            = refreshIntervalMillis.milliseconds
  )

  val metricOrchestrator = new MetricOrchestrator(
    metricSources    = List(githubRequestsQueueRepository, reportsService),
    lockService      = lock,
    metricRepository = metricRepository,
    metricRegistry   = metrics.defaultRegistry
  )

  actorSystem.scheduler.schedule(1.minute, refreshIntervalMillis.milliseconds) {
    metricOrchestrator
      .attemptMetricRefresh()
      .map(_.log())
      .recover({
        case e: RuntimeException =>
          logger.error(s"An error occurred processing metrics: ${e.getMessage}", e)
      })
  }
}
