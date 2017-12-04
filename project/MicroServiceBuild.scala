import sbt._
import play.sbt.PlayImport._
import play.core.PlayVersion

object MicroServiceBuild extends Build with MicroService {

  val appName = "leak-detection-service"

  override lazy val appDependencies: Seq[ModuleID] = compile ++ test()

  val compile = Seq(
    "uk.gov.hmrc"           %% "play-reactivemongo" % "6.1.0",
    "uk.gov.hmrc"           %% "bootstrap-play-25"  % "1.0.0",
    "com.github.pureconfig" %% "pureconfig"         % "0.8.0",
    "org.zeroturnaround"    % "zt-zip"              % "1.10",
    "commons-lang"          % "commons-lang"        % "2.6",
    "commons-io"            % "commons-io"          % "2.5",
    "org.scalaj"            %% "scalaj-http"        % "2.3.0",
    "org.typelevel"         %% "cats-core"          % "0.9.0",
    ws
  )

  def test(scope: String = "test,it") = Seq(
    "uk.gov.hmrc"            %% "hmrctest"           % "3.0.0"             % scope,
    "org.pegdown"            % "pegdown"             % "1.6.0"             % scope,
    "org.scalatest"          %% "scalatest"          % "2.2.6"             % scope,
    "com.typesafe.play"      %% "play-test"          % PlayVersion.current % scope,
    "org.mockito"            % "mockito-all"         % "1.10.19"           % scope,
    "com.github.tomakehurst" % "wiremock"            % "1.52"              % scope,
    "org.scalatest"          %% "scalatest"          % "3.0.4"             % scope,
    "com.lihaoyi"            %% "ammonite-ops"       % "1.0.3"             % scope,
    "uk.gov.hmrc"            %% "hmrctest"           % "3.0.0"             % scope,
    "org.scalatest"          %% "scalatest"          % "2.2.6"             % scope,
    "uk.gov.hmrc"            %% "reactivemongo-test" % "3.0.0"             % scope,
    "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.1"             % scope
  )

}
