/*
 * Copyright 2022 HM Revenue & Customs
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

import akka.actor.ActorSystem
import com.kenshoo.play.metrics.Metrics
import play.api.{Configuration, Logger}
import uk.gov.hmrc.leakdetection.persistence.GithubRequestsQueueRepository
import uk.gov.hmrc.leakdetection.services.LeaksService
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}
import uk.gov.hmrc.mongo.metrix.{MetricOrchestrator, MetricRepository}

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class MetricsScheduler @Inject()(
  actorSystem: ActorSystem,
  configuration: Configuration,
  metrics: Metrics,
  githubRequestsQueueRepository: GithubRequestsQueueRepository,
  leaksService: LeaksService,
  lockRepository: LockRepository,
  metricRepository: MetricRepository
)(implicit ec: ExecutionContext) {

  private val key = "queue.metricsGauges.interval"

  lazy val refreshIntervalMillis: Long = configuration.getMillis(key)

  val logger = Logger(this.getClass.getName)

  val lock = LockService(
    lockRepository = lockRepository,
    lockId         = "queue",
    ttl            = refreshIntervalMillis.milliseconds
  )

  val metricOrchestrator = new MetricOrchestrator(
    metricSources    = List(githubRequestsQueueRepository, leaksService),
    lockService      = lock,
    metricRepository = metricRepository,
    metricRegistry   = metrics.defaultRegistry
  )

  actorSystem.scheduler.scheduleWithFixedDelay(1.minute, refreshIntervalMillis.milliseconds)( () => {
    metricOrchestrator
      .attemptMetricRefresh()
      .map(_.log())
      .recover({
        case e: RuntimeException =>
          logger.error(s"An error occurred processing metrics: ${e.getMessage}", e)
      })
  })
}
