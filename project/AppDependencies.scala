import play.core.PlayVersion
import sbt._

object AppDependencies {

  val bootstrapVersion = "7.11.0"
  val hmrcMongoVersion = "1.3.0"

  val compile = Seq(
    "uk.gov.hmrc"            %% "bootstrap-backend-play-28"         % bootstrapVersion,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-work-item-repo-play-28" % hmrcMongoVersion,
    "org.zeroturnaround"     %  "zt-zip"                            % "1.14",
    "commons-io"             %  "commons-io"                        % "2.11.0",
    "org.typelevel"          %% "cats-core"                         % "2.6.1",
    "org.yaml"               %  "snakeyaml"                         % "1.17",
    "org.scala-lang.modules" %% "scala-parallel-collections"        % "1.0.0"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"   % bootstrapVersion    % Test,
    "org.mockito"            %% "mockito-scala"            % "1.10.2"            % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-28"  % hmrcMongoVersion    % Test,
    "org.scalatestplus"      %% "scalatestplus-scalacheck" % "3.1.0.0-RC2"       % Test,
    "com.lihaoyi"            %% "ammonite-ops"             % "1.6.9"             % Test
  )
}
