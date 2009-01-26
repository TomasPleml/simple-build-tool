/* sbt -- Simple Build Tool
 * Copyright 2008 Mark Harrah, David MacIver
 */
package sbt

/** The default project when no project is explicitly configured and the common base class for
* configuring a project.*/
class DefaultProject(val info: ProjectInfo) extends BasicScalaProject
class DefaultWebProject(val info: ProjectInfo) extends BasicWebScalaProject

import BasicScalaProject._

/** This class defines concrete instances of actions from ScalaProject using overridable paths,
* options, and configuration. */
abstract class BasicScalaProject extends ScalaProject with UnmanagedClasspathProject with ManagedProject
	with BasicProjectPaths with ReflectiveManagedProject
{
	/** The class to be run by the 'run' action.
	* See http://code.google.com/p/simple-build-tool/wiki/RunningProjectCode for details.*/
	def mainClass: Option[String] = None
	def dependencies = info.dependencies

	val mainCompileConditional = new CompileConditional(mainCompileConfiguration)
	val testCompileConditional = new CompileConditional(testCompileConfiguration)

	/** Declares all sources to be packaged by the package-src action.*/
	def allSources =
	{
		val sourceDirs = (mainScalaSourcePath +++ mainResourcesPath +++ testScalaSourcePath +++ testResourcesPath)
		descendents(sourceDirs, "*")
	}
	/** A PathFinder that selects all main sources.  It excludes paths that match 'defaultExcludes'.*/
	def mainSources = descendents(mainScalaSourcePath, "*.scala")
	/** A PathFinder that selects all test sources.  It excludes paths that match 'defaultExcludes'.*/
	def testSources = descendents(testScalaSourcePath, "*.scala")
	/** A PathFinder that selects all main resources.  It excludes paths that match 'defaultExcludes'.*/
	def mainResources = descendents(mainResourcesPath ##, "*")
	/** A PathFinder that selects all test resources.  It excludes paths that match 'defaultExcludes'.*/
	def testResources = descendents(testResourcesPath ##, "*")
	
	/** A PathFinder that selects all the classes compiled from the main sources.*/
	def mainClasses = (mainCompilePath ##) ** "*.class"
	/** A PathFinder that selects all the classes compiled from the test sources.*/
	def testClasses = (testCompilePath ##) ** "*.class"
	
	import Project._
	
	/** The dependency manager that represents inline declarations.  The default manager packages the information
	* from 'ivyXML', 'projectID', 'repositories', and 'libraryDependencies' and does not typically need to be
	* be overridden. */
	def manager = SimpleManager(ivyXML, true, projectID, repositories, libraryDependencies.toList: _*)
	
	/** The pattern for Ivy to use when retrieving dependencies into the local project.  Classpath management
	* depends on the first directory being [conf] and the extension being [ext].*/
	def outputPattern = "[conf]/[artifact](-[revision]).[ext]"
	/** Override this to specify the publications, configurations, and/or dependencies sections of an Ivy file.
	* See http://code.google.com/p/simple-build-tool/wiki/LibraryManagement for details.*/
	def ivyXML: scala.xml.NodeSeq = scala.xml.NodeSeq.Empty
	/** The base options passed to the 'update' action. */
	def baseUpdateOptions = Validate :: Synchronize :: QuietUpdate :: AddScalaToolsReleases :: Nil
	/** The options provided to the 'update' action.  This is by default the options in 'baseUpdateOptions'.
	* If 'manager' has any dependencies, resolvers, or inline Ivy XML (which by default happens when inline
	* dependency management is used), it is passed as the dependency manager.*/
	def updateOptions: Seq[ManagedOption] =
	{
		val m = manager
		if(m.dependencies.isEmpty && m.resolvers.isEmpty && ivyXML.isEmpty)
			baseUpdateOptions
		else
			LibraryManager(m) :: baseUpdateOptions
	}
	/** The options provided to the 'compile' action.*/
	def compileOptions: Seq[CompileOption] = Deprecation :: Nil
	/** The options provided to the 'test-compile' action, defaulting to those for the 'compile' action.*/
	def testCompileOptions: Seq[CompileOption] = compileOptions
	
	/** The options provided to the 'run' action.  These actions are passed as arguments to the main method
	* of the class specified in 'mainClass'.*/
	def runOptions: Seq[String] = Nil
	/** The options provided to the 'doc' and 'docTest' actions.*/
	def documentOptions: Seq[ScaladocOption] =
		LinkSource ::
		documentTitle(name + " " + version + " API") ::
		windowTitle(name + " " + version + " API") ::
		Nil
	/** The options provided to the 'test' action.  You can specify tests to exclude here.*/
	def testOptions: Seq[TestOption] = TestResources(testResourcesPath) :: Nil
	/** The options provided to the clean action.  You can add files to be removed here.*/
	def cleanOptions: Seq[CleanOption] =
		ClearAnalysis(mainCompileConditional.analysis) ::
		ClearAnalysis(testCompileConditional.analysis) ::
		Nil
	
	/** These are the directories that are created when a user makes a new project from sbt.*/
	private def directoriesToCreate: List[Path] =
		dependencyPath ::
		mainScalaSourcePath ::
		mainResourcesPath ::
		testScalaSourcePath ::
		testResourcesPath ::
		Nil
	
	/** This is called to create the initial directories when a user makes a new project from
	* sbt.*/
	override final def initializeDirectories()
	{
		FileUtilities.createDirectories(directoriesToCreate.map(_.asFile), log) match
		{
			case Some(errorMessage) => log.error("Could not initialize directory structure: " + errorMessage)
			case None => log.success("Successfully initialized directory structure.")
		}
	}
	import Configurations._
	/** The managed configuration to use when determining the classpath for a Scala interpreter session.*/
	def consoleConfiguration = Test
	
	/** A PathFinder that provides the classpath to pass to scaladoc.  It is the same as the compile classpath
	* by default. */
	def docClasspath = compileClasspath
	/** A PathFinder that provides the classpath to pass to the compiler.*/
	def compileClasspath = fullClasspath(Compile) +++ projectPathConcatenate[ManagedProject](_.managedClasspath(Provided, false)) +++ optionalClasspath
	/** A PathFinder that provides the classpath to use when unit testing.*/
	def testClasspath = projectPathConcatenate[BasicScalaProject](_.testCompilePath) +++ fullClasspath(Test) +++ optionalClasspath
	/** A PathFinder that provides the classpath to use when running the class specified in 'mainClass'.*/
	def runClasspath = fullClasspath(Runtime) +++ optionalClasspath
	/** A PathFinder that provides the classpath to use for a Scala interpreter session.*/
	def consoleClasspath = fullClasspath(consoleConfiguration) +++ optionalClasspath
	/** A PathFinder that corresponds to Maven's optional scope.  It includes any managed libraries in the
	* 'optional' configuration.*/
	def optionalClasspath = managedClasspath(Optional, false)

	/** This returns the classpath for this project only for the given configuration.  It by default includes
	* the main compiled classes for this project and the libraries in this project's unmanaged library directory
	* (lib) and the managed directory for the specified configuration.*/
	def projectClasspath(config: Configuration) = mainCompilePath +++ unmanagedClasspath +++ managedClasspath(config)
	
		
	/** The list of test frameworks to use for testing.  Note that adding frameworks to this list
	* for an active project currently requires an explicit 'clean'. */
	def testFrameworks: Iterable[TestFramework] = ScalaCheckFramework :: SpecsFramework :: ScalaTestFramework :: Nil
	
	def mainLabel = "main"
	def testLabel = "test"
		
	def mainCompileConfiguration = new MainCompileConfig
	def testCompileConfiguration = new TestCompileConfig
	abstract class BaseCompileConfig extends CompileConfiguration
	{
		def log = BasicScalaProject.this.log
		def projectPath = info.projectPath
	}
	class MainCompileConfig extends BaseCompileConfig
	{
		def label = mainLabel
		def sources = mainSources
		def outputDirectory = mainCompilePath
		def classpath = compileClasspath
		def analysisPath = mainAnalysisPath
		def testDefinitionClassNames = Nil
		def options = compileOptions.map(_.asString)
	}
	class TestCompileConfig extends BaseCompileConfig
	{
		def label = testLabel
		def sources = testSources
		def outputDirectory = testCompilePath
		def classpath = testClasspath
		def analysisPath = testAnalysisPath
		def testDefinitionClassNames = testFrameworks.map(_.testSuperClassName)
		def options = testCompileOptions.map(_.asString)
	}
	
	protected def compileAction = task { mainCompileConditional.run } describedAs MainCompileDescription
	protected def testCompileAction = task { testCompileConditional.run } dependsOn compile describedAs TestCompileDescription
	protected def cleanAction = cleanTask(outputPath, cleanOptions) describedAs CleanDescription
	protected def runAction = runTask(mainClass, runClasspath, runOptions).dependsOn(compile) describedAs RunDescription
	protected def consoleQuickAction = consoleTask(consoleClasspath) describedAs ConsoleQuickDescription
	protected def consoleAction = consoleTask(consoleClasspath).dependsOn(testCompile) describedAs ConsoleDescription
	protected def docAction = scaladocTask(mainLabel, mainSources, mainDocPath, docClasspath, documentOptions).dependsOn(compile) describedAs DocDescription
	protected def docTestAction = scaladocTask(testLabel, testSources, testDocPath, docClasspath, documentOptions).dependsOn(testCompile) describedAs TestDocDescription
	protected def testAction = testTask(testFrameworks, testClasspath, testCompileConditional.analysis, testOptions).dependsOn(testCompile) describedAs TestDescription
	protected def packageAction = packageTask(mainClasses +++ mainResources, outputPath, defaultJarName, mainClass.map(MainClassOption(_)).toList).dependsOn(compile) describedAs PackageDescription
	protected def packageTestAction = packageTask(testClasses +++ testResources, outputPath, defaultJarBaseName + "-test.jar").dependsOn(testCompile) describedAs TestPackageDescription
	protected def packageDocsAction = packageTask(mainDocPath ##, outputPath, defaultJarBaseName + "-docs.jar", Recursive).dependsOn(doc) describedAs DocPackageDescription
	protected def packageSrcAction = packageTask(allSources, outputPath, defaultJarBaseName + "-src.jar") describedAs SourcePackageDescription
	protected def docAllAction = (doc && docTest) describedAs DocAllDescription
	protected def packageAllAction = (`package` && packageTest && packageSrc && packageDocs) describedAs PackageAllDescription
	protected def graphAction = graphTask(graphPath, mainCompileConditional.analysis).dependsOn(compile)
	protected def updateAction = updateTask(outputPattern, managedDependencyPath, updateOptions) describedAs UpdateDescription
	protected def cleanLibAction = cleanLibTask(managedDependencyPath) describedAs CleanLibDescription
	protected def cleanCacheAction = cleanCacheTask(managedDependencyPath, updateOptions) describedAs CleanCacheDescription
	protected def incrementVersionAction = task { incrementVersionNumber(); None } describedAs IncrementVersionDescription
	protected def releaseAction = (test && packageAll && incrementVersion) describedAs ReleaseDescription
	
	lazy val compile = compileAction
	lazy val testCompile = testCompileAction
	lazy val clean = cleanAction
	lazy val run = runAction
	lazy val consoleQuick = consoleQuickAction
	lazy val console = consoleAction
	lazy val doc = docAction
	lazy val docTest = docTestAction
	lazy val test = testAction
	lazy val `package` = packageAction
	lazy val packageTest = packageTestAction
	lazy val packageDocs = packageDocsAction
	lazy val packageSrc = packageSrcAction
	lazy val docAll = docAllAction
	lazy val packageAll = packageAllAction
	lazy val graph = graphAction
	lazy val update = updateAction
	lazy val cleanLib = cleanLibAction
	lazy val cleanCache = cleanCacheAction
	lazy val incrementVersion = incrementVersionAction
	lazy val release = releaseAction
	
	
	/** The directories to which a project writes are listed here and is used
	* to check a project and its dependencies for collisions.*/
	override def outputDirectories = outputPath :: managedDependencyPath :: Nil
}
abstract class BasicWebScalaProject extends BasicScalaProject with WebScalaProject with WebProjectPaths
{
	import BasicWebScalaProject._
	
	lazy val prepareWebapp = prepareWebappAction
	protected def prepareWebappAction =
		prepareWebappTask(descendents(webappPath ##, "*") +++ extraWebappFiles, temporaryWarPath, runClasspath, extraWebappJars) dependsOn(compile)
	protected def extraWebappFiles: PathFinder = Path.emptyPathFinder
	private def extraWebappJars: Iterable[java.io.File] =
	{
		val externalJars = mainCompileConditional.analysis.allExternals.filter(ClasspathUtilities.isArchive)
		//For now, just include scala-library.jar
		externalJars.filter(_.getName == "scala-library.jar")
	}
	
	lazy val jettyRun = jettyRunAction
	protected def jettyRunAction =
		jettyRunTask(temporaryWarPath, jettyContextPath, testClasspath, "test") dependsOn(prepareWebapp) describedAs(JettyRunDescription)
		
	lazy val jettyStop = jettyStopAction
	protected def jettyStopAction = jettyStopTask describedAs(JettyStopDescription)
	
	override protected def packageAction = packageTask(descendents(temporaryWarPath ##, "*"), outputPath,
		defaultWarName, Nil) dependsOn(prepareWebapp) describedAs PackageWarDescription
}

object BasicScalaProject
{
	val CleanDescription =
		"Deletes all generated files (the target directory)."
	val MainCompileDescription =
		"Compiles main sources."
	val TestCompileDescription =
		"Compiles test sources."
	val TestDescription =
		"Runs all tests detected during compilation."
	val DocDescription =
		"Generates API documentation for main Scala source files using scaladoc."
	val TestDocDescription =
		"Generates API documentation for test Scala source files using scaladoc."
	val RunDescription =
		"Runs the main class specified for the project or starts the console if main class is not specified."
	val ConsoleDescription =
		"Starts the Scala interpreter with the project classes on the classpath."
	val ConsoleQuickDescription =
		"Starts the Scala interpreter with the project classes on the classpath without running compile first."
	val PackageDescription =
		"Creates a jar file containing main classes and resources."
	val TestPackageDescription =
		"Creates a jar file containing test classes and resources."
	val DocPackageDescription =
		"Creates a jar file containing generated API documentation."
	val SourcePackageDescription =
		"Creates a jar file containing all source files."
	val PackageAllDescription =
		"Executes all package tasks."
	val DocAllDescription =
		"Generates both main and test documentation."
	val UpdateDescription =
		"Resolves and retrieves automatically managed dependencies."
	val CleanLibDescription =
		"Deletes the managed library directory."
	val CleanCacheDescription =
		"Deletes the cache of artifacts downloaded for automatically managed dependencies."
	val IncrementVersionDescription =
		"Increments the micro part of the version (the third number) by one. (This is only valid for versions of the form #.#.#-*)"
	val ReleaseDescription =
		"Compiles, tests, generates documentation, packages, and increments the version."
}
object BasicWebScalaProject
{
	val PackageWarDescription =
		"Creates a war file."
	val JettyStopDescription =
		"Stops the Jetty server that was started with the jetty-run action."
	val JettyRunDescription =
		"Starts the Jetty server and serves this project as a web application."
}
trait BasicDependencyPaths extends Project
{
	import BasicProjectPaths._
	def dependencyDirectoryName = DefaultDependencyDirectoryName
	def managedDirectoryName = DefaultManagedDirectoryName
	def dependencyPath = path(dependencyDirectoryName)
	def managedDependencyPath = path(managedDirectoryName)
}
trait BasicProjectPaths extends BasicDependencyPaths
{
	import BasicProjectPaths._
	
	//////////// Paths ///////////
	
	def defaultJarBaseName = name + "-" + version.toString
	def defaultJarName = defaultJarBaseName + ".jar"
	def defaultWarName = defaultJarBaseName + ".war"
	
	def outputDirectoryName = DefaultOutputDirectoryName
	def sourceDirectoryName = DefaultSourceDirectoryName
	def mainDirectoryName = DefaultMainDirectoryName
	def scalaDirectoryName = DefaultScalaDirectoryName
	def resourcesDirectoryName = DefaultResourcesDirectoryName
	def testDirectoryName = DefaultTestDirectoryName
	def mainCompileDirectoryName = DefaultMainCompileDirectoryName
	def testCompileDirectoryName = DefaultTestCompileDirectoryName
	def docDirectoryName = DefaultDocDirectoryName
	def apiDirectoryName = DefaultAPIDirectoryName
	def graphDirectoryName = DefaultGraphDirectoryName
	def mainAnalysisDirectoryName = DefaultMainAnalysisDirectoryName
	def testAnalysisDirectoryName = DefaultTestAnalysisDirectoryName
	
	def outputPath = path(outputDirectoryName)
	def sourcePath = path(sourceDirectoryName)
	
	def mainSourcePath = sourcePath / mainDirectoryName
	def mainScalaSourcePath = mainSourcePath / scalaDirectoryName
	def mainResourcesPath = mainSourcePath / resourcesDirectoryName
	def mainDocPath = docPath / mainDirectoryName / apiDirectoryName
	def mainCompilePath = outputPath / mainCompileDirectoryName
	def mainAnalysisPath = outputPath / mainAnalysisDirectoryName
	
	def testSourcePath = sourcePath / testDirectoryName
	def testScalaSourcePath = testSourcePath / scalaDirectoryName
	def testResourcesPath = testSourcePath / resourcesDirectoryName
	def testDocPath = docPath / testDirectoryName / apiDirectoryName
	def testCompilePath = outputPath / testCompileDirectoryName
	def testAnalysisPath = outputPath / testAnalysisDirectoryName
	
	def docPath = outputPath / docDirectoryName
	def graphPath = outputPath / graphDirectoryName
}
object BasicProjectPaths
{
	val DefaultSourceDirectoryName = "src"
	val DefaultOutputDirectoryName = "target"
	val DefaultMainCompileDirectoryName = "classes"
	val DefaultTestCompileDirectoryName = "test-classes"
	val DefaultDocDirectoryName = "doc"
	val DefaultAPIDirectoryName = "api"
	val DefaultGraphDirectoryName = "graph"
	val DefaultManagedDirectoryName = "lib_managed"
	val DefaultMainAnalysisDirectoryName = "analysis"
	val DefaultTestAnalysisDirectoryName = "test-analysis"
	
	val DefaultMainDirectoryName = "main"
	val DefaultScalaDirectoryName = "scala"
	val DefaultResourcesDirectoryName = "resources"
	val DefaultTestDirectoryName = "test"
	val DefaultDependencyDirectoryName = "lib"
}
trait WebProjectPaths extends BasicProjectPaths
{
	import WebProjectPaths._
	def temporaryWarPath = outputDirectoryName / webappDirectoryName
	def webappPath = mainSourcePath / webappDirectoryName
	def webappDirectoryName = DefaultWebappDirectoryName
	def jettyContextPath = DefaultJettyContextPath
}
object WebProjectPaths
{
	val DefaultWebappDirectoryName = "webapp"
	val DefaultJettyContextPath = "/"
}