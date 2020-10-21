import play.core.PlayVersion.current
import sbt._

object AppDependencies {

  val compile = Seq(
    "uk.gov.hmrc"           %% "bootstrap-backend-play-27"  % "2.23.0",
    "uk.gov.hmrc"           %% "play-ui"                    % "8.12.0-play-27",
    "uk.gov.hmrc"           %% "metrix"                     % "4.7.0-play-27",
    "uk.gov.hmrc"           %% "work-item-repo"             % "7.10.0-play-27",
    "uk.gov.hmrc"           %% "simple-reactivemongo"       % "7.30.0-play-27",
    "uk.gov.hmrc"           %% "play-scheduling"            % "7.4.0-play-26",
    "com.github.pureconfig" %% "pureconfig"                 % "0.8.0",
    "org.zeroturnaround"    % "zt-zip"                      % "1.10",
    "commons-lang"          % "commons-lang"                % "2.6",
    "commons-io"            % "commons-io"                  % "2.5",
    "org.scalaj"            %% "scalaj-http"                % "2.3.0",
    "org.typelevel"         %% "cats-core"                  % "0.9.0",
    "org.yaml"              % "snakeyaml"                   % "1.17",
    "com.lihaoyi"           %% "pprint"                     % "0.5.3",
    "com.lihaoyi"           %% "ammonite-ops"               % "1.0.3"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-27"   % "2.23.0"         % Test,
    "org.scalatest"          %% "scalatest"                % "3.1.2"          % Test,
    "com.vladsch.flexmark"   %  "flexmark-all"             % "0.35.10"        % Test,
    "com.typesafe.play"      %% "play-test"                % current          % Test,
    "org.mockito"            %% "mockito-scala"            % "1.10.2"         % Test,
    "uk.gov.hmrc"            %% "reactivemongo-test"       % "4.21.0-play-27" % Test,
    "org.scalatestplus.play" %% "scalatestplus-play"       % "4.0.3"          % Test,
    "org.scalatestplus"      %% "scalatestplus-scalacheck" % "3.1.0.0-RC2"    % Test,
    "org.scalacheck"         %% "scalacheck"               % "1.13.4"         % Test,
    "com.github.tomakehurst"  % "wiremock-standalone"      % "2.27.1"         % Test
  )

}
