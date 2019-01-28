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
import play.api.{Configuration, Logger}
import uk.gov.hmrc.leakdetection.services.ScanningService
import uk.gov.hmrc.play.scheduling.ExclusiveScheduledJob

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class ScanRepositoriesScheduler @Inject()(
  actorSystem: ActorSystem,
  configuration: Configuration,
  scanningService: ScanningService)
    extends ExclusiveScheduledJob {

  override def name: String = "scanner"

  override def executeInMutex(implicit ec: ExecutionContext): Future[Result] =
    scanningService.scanAll.map(reports => Result(s"Processed ${reports.size} github requests"))

  private def durationFromConfig(propertyKey: String): FiniteDuration = {
    val key = s"scheduling.$name.$propertyKey"
    configuration
      .getMilliseconds(key)
      .getOrElse(throw new IllegalStateException(s"Config key $key missing"))
      .milliseconds
  }

  lazy val initialDelay: FiniteDuration = durationFromConfig("initialDelay")
  lazy val interval: FiniteDuration     = durationFromConfig("interval")

  actorSystem.scheduler.schedule(initialDelay, interval) {
    Logger.info("Scheduled scanning job triggered")
    execute.onComplete {
      case Success(Result(message)) =>
        Logger.info(s"Completed scanning job: $message")
      case Failure(throwable) =>
        Logger.error(s"Exception running scanning job", throwable)
    }
  }
}
