import play.sbt.routes.RoutesKeys
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

lazy val microservice = Project("leak-detection", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(
    majorVersion := 0,
    scalaVersion := "2.13.10",
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    PlayKeys.playDefaultPort := 8855,
    resolvers     += Resolver.jcenterRepo,
    scalacOptions += "-Yrangepos",
    scalacOptions += "-Wconf:src=routes/.*:s"
  )
  .settings(publishingSettings: _*)

RoutesKeys.routesImport ++= Seq(
  "uk.gov.hmrc.leakdetection.model.Branch",
  "uk.gov.hmrc.leakdetection.model.Repository",
  "uk.gov.hmrc.leakdetection.model.ReportId",
  "uk.gov.hmrc.leakdetection.model.RunMode",
  "uk.gov.hmrc.leakdetection.model.RunMode._"
)
