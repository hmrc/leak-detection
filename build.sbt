import uk.gov.hmrc.DefaultBuildSettings.{defaultSettings, scalaSettings}
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings
import uk.gov.hmrc.SbtArtifactory

val silencerVersion = "1.7.2"

lazy val microservice = Project("leak-detection", file("."))
  .enablePlugins(
    Seq(play.sbt.PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin, SbtArtifactory): _*)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(majorVersion := 0)
  .settings(scalaSettings: _*)
  .settings(publishingSettings: _*)
  .settings(defaultSettings(): _*)
  .settings(PlayKeys.playDefaultPort := 8855)
  .settings(
    scalaVersion                     := "2.12.13",
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    evictionWarningOptions in update := EvictionWarningOptions.default.withWarnScalaVersionEviction(false)
  )
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(
    scalacOptions ++= List(
      "-Yrangepos"
    )
  )
  .settings(
    // Use the silencer plugin to suppress warnings from unused imports in routes etc.
    scalacOptions += "-P:silencer:pathFilters=routes;resources",
    libraryDependencies ++= Seq(
      compilerPlugin("com.github.ghik" % "silencer-plugin" % silencerVersion cross CrossVersion.full),
      "com.github.ghik" % "silencer-lib" % silencerVersion % Provided cross CrossVersion.full
    )
  )
