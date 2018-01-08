/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.leakdetection

import akka.actor.ActorSystem
import play.api.BuiltInComponents
import play.api.mvc.{Handler, RequestHeader}
import play.api.routing.Router
import play.core.server.{NettyServer, NettyServerComponents, ServerConfig}

object TestServer {
  def apply(routes: PartialFunction[RequestHeader, Handler]): NettyServer =
    new NettyServerComponents with BuiltInComponents {
      lazy val router: Router        = Router.from(routes)
      override lazy val serverConfig = ServerConfig(port = Some(0))
      override lazy val actorSystem  = ActorSystem()
    }.server
}
