import play.core.PlayVersion
import sbt._

object AppDependencies {

  val bootstrapVersion = "9.13.0"
  val hmrcMongoVersion = "2.6.0"

  val compile = Seq(
    "uk.gov.hmrc"            %% "bootstrap-backend-play-30"         % bootstrapVersion,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-work-item-repo-play-30" % hmrcMongoVersion,
    "org.zeroturnaround"     %  "zt-zip"                            % "1.16",
    "commons-io"             %  "commons-io"                        % "2.15.0",
    "org.typelevel"          %% "cats-core"                         % "2.13.0",
    "org.yaml"               %  "snakeyaml"                         % "2.2",
    "org.scala-lang.modules" %% "scala-parallel-collections"        % "1.0.4"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"   % bootstrapVersion    % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30"  % hmrcMongoVersion    % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"          % "3.2.18.0"          % Test
  )
}
