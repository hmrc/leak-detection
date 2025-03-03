import play.sbt.routes.RoutesKeys

lazy val microservice = Project("leak-detection", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(
    majorVersion := 0,
    scalaVersion := "3.3.5",
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    PlayKeys.playDefaultPort := 8855,
    resolvers     += Resolver.jcenterRepo,
    scalacOptions += "-Wconf:src=routes/.*:s",
    scalacOptions += "-Wconf:msg=Flag.*repeatedly:s"
  )

RoutesKeys.routesImport ++= Seq(
  "uk.gov.hmrc.leakdetection.model.Branch",
  "uk.gov.hmrc.leakdetection.model.Repository",
  "uk.gov.hmrc.leakdetection.model.ReportId",
  "uk.gov.hmrc.leakdetection.model.RunMode",
  "uk.gov.hmrc.leakdetection.model.RunMode._"
)
