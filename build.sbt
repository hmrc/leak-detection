import play.sbt.routes.RoutesKeys
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

val silencerVersion = "1.7.2"

lazy val microservice = Project("leak-detection", file("."))
  .enablePlugins(
    Seq(play.sbt.PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin): _*)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(majorVersion := 0)
  .settings(publishingSettings: _*)
  .settings(PlayKeys.playDefaultPort := 8855)
  .settings(
    scalaVersion                     := "2.12.13",
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test
  )
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(
    scalacOptions ++= List(
      "-Yrangepos"
    )
  )
  .settings(
    // Use the silencer plugin to suppress warnings from unused imports in routes etc.
    scalacOptions += "-P:silencer:pathFilters=views;routes",
    libraryDependencies ++= Seq(
      compilerPlugin("com.github.ghik" % "silencer-plugin" % silencerVersion cross CrossVersion.full),
      "com.github.ghik" % "silencer-lib" % silencerVersion % Provided cross CrossVersion.full
    )
  )

RoutesKeys.routesImport ++= Seq(
  "uk.gov.hmrc.leakdetection.model.Branch",
  "uk.gov.hmrc.leakdetection.model.Repository",
  "uk.gov.hmrc.leakdetection.model.ReportId")
