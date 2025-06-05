//> using scala 3.6.3
//> using dep "uk.gov.hmrc::leak-detection:0.222.0-SNAPSHOT"

import java.nio.file.{Path, Paths, Files}
import java.io.File
import uk.gov.hmrc.leakdetection.services.RulesExemptionParser
import uk.gov.hmrc.leakdetection.scanner.RegexMatchingEngine
import uk.gov.hmrc.leakdetection.config.*
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import org.yaml.snakeyaml.Yaml
import scala.io.Source
import scala.jdk.CollectionConverters.*

@main def run(args: String*) =
  if args.isEmpty then
      println("Usage: ./local-scan.sh <path-to-repository>")
      sys.exit(1)
  
  val pathString = args.head
  val path = Paths.get(pathString).toAbsolutePath.normalize()

  if !Files.exists(path) then
      println(s"Error: Path does not exist: $pathString")
      sys.exit(1)
  
  if !Files.isDirectory(path) then
      println(s"Error: Path is not a directory: $pathString")
      sys.exit(1)

  val repoDir: File = path.toFile

  val repoYamlFile = File(repoDir, "repository.yaml")
  val isPrivate: Boolean = 
    if repoYamlFile.exists() then
      val source = Source.fromFile(repoYamlFile)
      try
        val content = source.mkString
        val yaml = Yaml().load(content).asInstanceOf[java.util.Map[String, String]]
        yaml.asScala.get("repoVisibility") match
          case Some(visibility) if visibility.startsWith("private") => true
          case Some(visibility) if visibility.startsWith("public") => false
          case _ => 
            println("Unable to determine repository visibility from repository.yaml")
            sys.exit(1)
      catch
        case _ => 
          println("Error reading repository.yaml")
          sys.exit(1)
      finally
        source.close()
    else
      println("repository.yaml not found")
      sys.exit(1)

  val exemptions: List[RuleExemption] =
    RulesExemptionParser.parseServiceSpecificExemptions(repoDir).fold(
        warning =>
          println(s"Error parsing exemptions: $warning")
          sys.exit(1)
        ,
        identity
      )

  val appConfig: AppConfig = AppConfigProvider(Configuration(ConfigFactory.load())).get
  val rules = if isPrivate then appConfig.allRules.privateRules else appConfig.allRules.publicRules

  val engine = RegexMatchingEngine(rules, appConfig.maxLineLength)
  val results = engine.run(repoDir, exemptions).filterNot(_.isExcluded)

  println(s"Scan complete! Found ${results.length} potential leaks")
  results.foreach(result => println(s"  ${result.ruleId}: ${result.filePath}:${result.lineNumber}"))
