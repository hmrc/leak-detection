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

package uk.gov.hmrc.leakdetection.scheduled

import javax.inject.Inject

import akka.actor.ActorSystem
import play.api.{Configuration, Logger}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.leakdetection.services.ScanningService

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

class ScanRepositoriesScheduler @Inject()(
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  configuration       : Configuration,
  scanningService     : ScanningService
)(implicit ec: ExecutionContext
) {
  private val logger = Logger(getClass)

  implicit val hc = uk.gov.hmrc.http.HeaderCarrier()

  private def execute(implicit ec: ExecutionContext): Future[Result] =
    scanningService.scanAll.map(count => Result(s"Processed $count github requests"))

  private lazy val enabled: Boolean =
    configuration.get[Boolean]("scheduling.scanner.enabled")

  private lazy val initialDelay: FiniteDuration =
    configuration.get[FiniteDuration]("scheduling.scanner.initialDelay")

  private lazy val interval: FiniteDuration =
    configuration.get[FiniteDuration]("scheduling.scanner.interval")

  if (enabled) {
    val cancellable =
      actorSystem.scheduler.scheduleAtFixedRate(initialDelay, interval){ () =>
        logger.info("Scheduled scanning job triggered")
        execute.onComplete {
          case Success(Result(message)) =>
            logger.info(s"Completed scanning job: $message")
          case Failure(throwable) =>
            logger.error(s"Exception running scanning job", throwable)
        }
      }
    applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
  } else
    logger.warn(s"The ScanRepositoriesScheduler has been disabled.")

  case class Result(message: String)
}
