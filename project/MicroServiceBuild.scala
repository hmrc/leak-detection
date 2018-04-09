import sbt._
import play.sbt.PlayImport._
import play.core.PlayVersion

object MicroServiceBuild extends Build with MicroService {

  val appName = "leak-detection"

  override lazy val appDependencies: Seq[ModuleID] = compile ++ test

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"           %% "play-reactivemongo" % "6.2.0",
    "uk.gov.hmrc"           %% "bootstrap-play-25"  % "1.5.0",
    "uk.gov.hmrc"           %% "play-ui"            % "7.14.0",
    "uk.gov.hmrc"           %% "metrix"             % "2.0.0",
    "uk.gov.hmrc"           %% "work-item-repo"     % "5.2.0",
    "uk.gov.hmrc"           %% "play-scheduling"    % "4.1.0",
    "com.github.pureconfig" %% "pureconfig"         % "0.8.0",
    "org.zeroturnaround"    % "zt-zip"              % "1.10",
    "commons-lang"          % "commons-lang"        % "2.6",
    "commons-io"            % "commons-io"          % "2.5",
    "org.scalaj"            %% "scalaj-http"        % "2.3.0",
    "org.typelevel"         %% "cats-core"          % "0.9.0",
    "org.yaml"              % "snakeyaml"           % "1.17",
    "com.lihaoyi"           %% "pprint"             % "0.5.3",
    "com.lihaoyi"           %% "ammonite-ops"       % "1.0.3"
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "hmrctest"           % "3.0.0",
    "org.pegdown"            % "pegdown"             % "1.6.0",
    "com.typesafe.play"      %% "play-test"          % PlayVersion.current,
    "org.mockito"            % "mockito-all"         % "1.10.19",
    "uk.gov.hmrc"            %% "reactivemongo-test" % "3.1.0",
    "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.1",
    "org.scalacheck"         %% "scalacheck"         % "1.13.4",
    "com.github.tomakehurst" % "wiremock"            % "2.16.0"
  ).map(_ % "test, it")

}
