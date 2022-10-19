import play.core.PlayVersion
import sbt._

object AppDependencies {

  val bootstrapVersion = "6.2.0"
  val hmrcMongoVersion = "0.71.0"

  val compile = Seq(
    "uk.gov.hmrc"           %% "auth-client"                       % "5.7.0-play-28",
    "uk.gov.hmrc"           %% "bootstrap-backend-play-28"         % bootstrapVersion,
    "uk.gov.hmrc"           %% "play-ui"                           % "9.8.0-play-28",
    "uk.gov.hmrc.mongo"     %% "hmrc-mongo-work-item-repo-play-28" % hmrcMongoVersion,
    "com.github.pureconfig" %% "pureconfig"                        % "0.8.0",
    "org.zeroturnaround"    %  "zt-zip"                            % "1.14",
    "commons-lang"          %  "commons-lang"                      % "2.6",
    "commons-io"            %  "commons-io"                        % "2.11.0",
    "org.typelevel"         %% "cats-core"                         % "2.6.1",
    "org.yaml"              %  "snakeyaml"                         % "1.17",
    "com.lihaoyi"           %% "pprint"                            % "0.5.3",
    "com.lihaoyi"           %% "ammonite-ops"                      % "1.0.3"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"   % bootstrapVersion    % Test,
    "com.typesafe.play"      %% "play-test"                % PlayVersion.current % Test,
    "org.mockito"            %% "mockito-scala"            % "1.10.2"            % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-28"  % hmrcMongoVersion    % Test,
    "org.scalatestplus.play" %% "scalatestplus-play"       % "4.0.3"             % Test,
    "org.scalatestplus"      %% "scalatestplus-scalacheck" % "3.1.0.0-RC2"       % Test,
    "org.scalacheck"         %% "scalacheck"               % "1.13.4"            % Test,
    "com.vladsch.flexmark"   %  "flexmark-all"             % "0.35.10"           % Test,
    "com.github.tomakehurst"  % "wiremock-standalone"      % "2.27.1"            % Test
  )
}
