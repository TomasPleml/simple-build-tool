/* sbt -- Simple Build Tool
 * Copyright 2008, 2009 Mark Harrah, David MacIver
 */
package sbt

import FileUtilities._
import java.io.File
import java.util.jar.{Attributes, Manifest}

trait ScalaProject extends Project with FileTasks
{
	import ScalaProject._
	
	def errorTask(message: String) = task{ Some(message) }
	
	case class CompileOption(val asString: String) extends ActionOption
	trait PackageOption extends ActionOption
	trait TestOption extends ActionOption
	trait CleanOption extends ActionOption
	case class ClearAnalysis(analysis: TaskAnalysis[_, _, _]) extends CleanOption
	
	case class ExcludeTests(tests: Iterable[String]) extends TestOption
	case class TestListeners(listeners: Iterable[TestReportListener]) extends TestOption
	case class TestFilter(filterTest: String => Boolean) extends TestOption
	
	case class JarManifest(m: Manifest) extends PackageOption
	{
		assert(m != null)
	}
	case class MainClass(mainClassName: String) extends PackageOption
	case object Recursive extends PackageOption
	
	val Deprecation = CompileOption("-deprecation")
	val ExplainTypes = CompileOption("-explaintypes")
	val Optimize = CompileOption("-optimise")
	def Optimise = Optimize
	val Verbose = CompileOption("-verbose")
	val Unchecked = CompileOption("-unchecked")
	val DisableWarnings = CompileOption("-nowarn")
	def target(target: Target.Value) = CompileOption("-target:" + target)
	object Target extends Enumeration
	{
		val Java1_5 = Value("jvm-1.5")
		val Java1_4 = Value("jvm-1.4")
		val Msil = Value("msil")
	}
	
	trait ScaladocOption extends ActionOption
	{
		def asList: List[String]
	}
	case class SimpleDocOption(optionValue: String) extends ScaladocOption
	{
		def asList = List(optionValue)
	}
	case class CompoundDocOption(label: String, value: String) extends ScaladocOption
	{
		def asList = List(label, value)
	}
	val LinkSource = SimpleDocOption("-linksource")
	val NoComment = SimpleDocOption("-nocomment")
	def access(access: Access.Value) = SimpleDocOption("-access:" + access)
	def documentBottom(bottomText: String) = CompoundDocOption("-bottom", bottomText)
	def documentCharset(charset: String) = CompoundDocOption("-charset", charset)
	def documentTitle(title: String) = CompoundDocOption("-doctitle", title)
	def documentFooter(footerText: String) = CompoundDocOption("-footer", footerText)
	def documentHeader(headerText: String) = CompoundDocOption("-header", headerText)
	def stylesheetFile(path: Path) = CompoundDocOption("-stylesheetfile", path.asFile.getAbsolutePath)
	def documentTop(topText: String) = CompoundDocOption("-top", topText)
	def windowTitle(title: String) = CompoundDocOption("-windowtitle", title)
	
	object Access extends Enumeration
	{
		val Public = Value("public")
		val Default = Value("protected")
		val Private = Value("private")
	}

	def consoleTask(classpath : PathFinder): Task = 
		interactiveTask { Run.console(classpath.get, log) }

	def runTask(mainClass: Option[String], classpath: PathFinder, options: String*): Task =
		runTask(mainClass, classpath, options)
	def runTask(mainClass: Option[String], classpath: PathFinder, options: => Seq[String]): Task =
		task
		{
			if(mainClass.isDefined)
				Run(mainClass, classpath.get, options, log)
			else
				Some("No main class specified.")
		}

	def cleanTask(paths: PathFinder, options: CleanOption*): Task =
		cleanTask(paths, options)
	def cleanTask(paths: PathFinder, options: => Seq[CleanOption]): Task =
		task
		{
			val pathClean = FileUtilities.clean(paths.get, log)
			for(ClearAnalysis(analysis) <- options)
			{
				analysis.clear()
				analysis.save()
			}
			pathClean
		}
		
	def syncTask(sourceDirectory: Path, destinationDirectory: Path): Task =
		task { FileUtilities.sync(sourceDirectory, destinationDirectory, log) }
	def copyTask(sources: PathFinder, destinationDirectory: Path): Task =
		task { FileUtilities.copy(sources.get, destinationDirectory, log).left.toOption }

	def testTask(frameworks: Iterable[TestFramework], classpath: PathFinder, analysis: CompileAnalysis, options: TestOption*): Task =
		testTask(frameworks, classpath, analysis, options)
	def testTask(frameworks: Iterable[TestFramework], classpath: PathFinder, analysis: CompileAnalysis, options: => Seq[TestOption]): Task =
		task{ doTests(frameworks, classpath, analysis, options) }

	def graphTask(outputDirectory: Path, analysis: CompileAnalysis): Task = task { DotGraph(analysis, outputDirectory, log) }
	def scaladocTask(label: String, sources: PathFinder, outputDirectory: Path, classpath: PathFinder, options: ScaladocOption*): Task =
		scaladocTask(label, sources, outputDirectory, classpath, options)
	def scaladocTask(label: String, sources: PathFinder, outputDirectory: Path, classpath: PathFinder, options: => Seq[ScaladocOption]): Task =
		task
		{
			val classpathString = Path.makeString(classpath.get)
			Scaladoc(label, sources.get, classpathString, outputDirectory, options.flatMap(_.asList), log)
		}

	def packageTask(sources: PathFinder, outputDirectory: Path, jarName: => String, options: PackageOption*): Task =
		packageTask(sources, outputDirectory, jarName, options)
	def packageTask(sources: PathFinder, outputDirectory: Path, jarName: => String, options: => Seq[PackageOption]): Task =
		fileTask("package", (outputDirectory / jarName) from sources)
		{
			import scala.collection.jcl.Map
			/** Copies the mappings in a2 to a1, mutating a1. */
			def mergeAttributes(a1: Attributes, a2: Attributes)
			{
				for( (key, value) <- Map(a2))
					a1.put(key, value)
			}

			import scala.collection.mutable.ListBuffer
			val manifest = new Manifest
			var recursive = false
			for(option <- options)
			{
				option match
				{
					case JarManifest(mergeManifest) => 
					{
						mergeAttributes(manifest.getMainAttributes, mergeManifest.getMainAttributes)
						val entryMap = Map(manifest.getEntries)
						for((key, value) <- Map(mergeManifest.getEntries))
						{
							entryMap.get(key) match
							{
								case Some(attributes) => mergeAttributes(attributes, value)
								case None => entryMap.put(key, value)
							}
						}
					}
					case Recursive => recursive = true
					case MainClass(mainClassName) =>
						manifest.getMainAttributes.put(Attributes.Name.MAIN_CLASS, mainClassName)
					case _ => log.warn("Ignored unknown package option " + option)
				}
			}
			val jarPath = outputDirectory / jarName
			FileUtilities.clean(jarPath :: Nil, log) orElse
			FileUtilities.jar(sources.get, jarPath, manifest, recursive, log)
		}
	def zipTask(sources: PathFinder, outputDirectory: Path, zipName: => String): Task =
		fileTask("zip", (outputDirectory / zipName) from sources)
		{
			val zipPath = outputDirectory / zipName
			FileUtilities.zip(sources.get, zipPath, false, log)
		}
	def incrementVersionNumber()
	{
		projectVersion.get match
		{
			case Some(v: BasicVersion) =>
			{
				val newVersion = incrementImpl(v)
				log.info("Changing version to " + newVersion)
				projectVersion() = newVersion
			}
			case a => ()
		}
	}
	protected def incrementImpl(v: BasicVersion): Version = v.incrementMicro
	protected def doTests(frameworks: Iterable[TestFramework], classpath: PathFinder, analysis: CompileAnalysis, options: => Seq[TestOption]): Option[String] = {
		import scala.collection.mutable.HashSet

			val testFilters = for(TestFilter(include) <- options) yield include
			val excludeTests = for(ExcludeTests(exclude) <- options) yield exclude
			val excludeTestsSet = HashSet.empty[String] ++ excludeTests.flatMap(x => x)
			if(excludeTestsSet.size > 0 && log.atLevel(Level.Debug))
			{
				log.debug("Excluding tests: ")
				excludeTestsSet.foreach(test => log.debug("\t" + test))
			}
			def includeTest(test: TestDefinition) = !excludeTestsSet.contains(test.testClassName) && testFilters.forall(filter => filter(test.testClassName))
			val tests = HashSet.empty[TestDefinition] ++ analysis.allTests.filter(includeTest)
			val listeners = (for(TestListeners(listeners) <- options) yield listeners).flatMap(x => x)
			TestFramework.runTests(frameworks, classpath.get, tests, log, listeners)
	}
	
	protected def testQuickMethod(testAnalysis: CompileAnalysis, options: => Seq[TestOption])(toRun: Seq[TestOption] => Task) =
		task { tests =>
			val (exactFilters, testFilters) = tests.toList.map(GlobFilter.apply).partition(_.isInstanceOf[ExactFilter])
			val includeTests = exactFilters.map(_.asInstanceOf[ExactFilter].matchName)
			val toCheck = scala.collection.mutable.HashSet(includeTests: _*)
			toCheck --= testAnalysis.allTests.map(_.testClassName)
			if(!toCheck.isEmpty && log.atLevel(Level.Warn))
			{
				log.warn("Test(s) not found:")
				toCheck.foreach(test => log.warn("\t" + test))
			}
			val includeTestsSet = scala.collection.mutable.HashSet(includeTests: _*)
			val newOptions =
				if(includeTests.isEmpty && testFilters.isEmpty) options
				else TestFilter(test => includeTestsSet.contains(test) || testFilters.exists(_.accept(test))) :: options.toList
			toRun(newOptions)
		} completeWith testAnalysis.allTests.map(_.testClassName)
}
trait WebScalaProject extends ScalaProject
{
	protected def prepareWebappTask(webappContents: PathFinder, warPath: => Path, classpath: PathFinder, extraJars: => Iterable[File]) =
		task
		{
			val webInfPath = warPath / "WEB-INF"
			val webLibDirectory = webInfPath / "lib"
			val classesTargetDirectory = webInfPath / "classes"
			
			val (libs, directories) = classpath.get.toList.partition(ClasspathUtilities.isArchive)
			val classesAndResources = descendents(Path.lazyPathFinder(directories) ##, "*")
			if(log.atLevel(Level.Debug))
				directories.foreach(d => log.debug(" Copying the contents of directory " + d + " to " + classesTargetDirectory))
			
			import FileUtilities.{copy, copyFlat, copyFilesFlat, clean}
			(copy(webappContents.get, warPath, log).right flatMap { copiedWebapp =>
			copy(classesAndResources.get, classesTargetDirectory, log).right flatMap { copiedClasses =>
			copyFlat(libs, webLibDirectory, log).right flatMap { copiedLibs =>
			copyFilesFlat(extraJars, webLibDirectory, log).right flatMap { copiedExtraLibs =>
				{
					val toRemove = scala.collection.mutable.HashSet((warPath ** "*").get.toSeq : _*)
					toRemove --= copiedWebapp
					toRemove --= copiedClasses
					toRemove --= copiedLibs
					toRemove --= copiedExtraLibs
					val (directories, files) = toRemove.toList.partition(_.isDirectory)
					if(log.atLevel(Level.Debug))
						files.foreach(r => log.debug("Pruning file " + r))
					val result =
						clean(files, true, log) orElse
						{
							val emptyDirectories = directories.filter(directory => directory.asFile.listFiles.isEmpty)
							if(log.atLevel(Level.Debug))
								emptyDirectories.foreach(r => log.debug("Pruning directory " + r))
							clean(emptyDirectories, true, log)
						}
					result.toLeft(())
				}
			}}}}).left.toOption
		}
	def jettyRunTask(warPath: => Path, defaultContextPath: => String, classpath: PathFinder, classpathName: String, scanDirectories: Seq[File], scanInterval: Int): Task =
		task { JettyRun(classpath.get, classpathName, warPath, defaultContextPath, scanDirectories, scanInterval, log) }
	def jettyRunTask(warPath: => Path, defaultContextPath: => String, classpath: PathFinder, classpathName: String,
		jettyConfigurationXML: scala.xml.NodeSeq, jettyConfigurationFiles: Seq[File]): Task =
			jettyRunTask(warPath, defaultContextPath, classpath, classpathName, jettyConfigurationXML, jettyConfigurationFiles)
	def jettyStopTask = task { JettyRun.stop(); None }
}
object ScalaProject
{
	val AnalysisDirectoryName = "analysis"
	val MainClassKey = "Main-Class"
	val TestResourcesProperty = "sbt.test.resources"
}