/* sbt -- Simple Build Tool
 * Copyright 2008 Mark Harrah
 */
package sbt

trait Conditional[Source, Product, External] extends NotNull
{
	type AnalysisType <: TaskAnalysis[Source, Product, External]
	val analysis: AnalysisType = loadAnalysis
	
	protected def loadAnalysis: AnalysisType
	protected def log: Logger

	protected def productType: String
	protected def productTypePlural: String
	
	protected def sourcesToProcess: Iterable[Source]
	
	protected def sourceExists(source: Source): Boolean
	protected def sourceLastModified(source: Source): Long
	
	protected def productExists(product: Product): Boolean
	protected def productLastModified(product: Product): Long
	
	protected def externalInfo(externals: Iterable[External]): Iterable[(External, ExternalInfo)]
	
	protected def execute(cAnalysis: ConditionalAnalysis): Option[String]
	
	final case class ExternalInfo(available: Boolean, lastModified: Long) extends NotNull
	trait ConditionalAnalysis extends NotNull
	{
		def dirtySources: Iterable[Source]
		def cleanSources: Iterable[Source]
		def directlyModifiedSourcesCount: Int
		def invalidatedSourcesCount: Int
		def removedSourcesCount: Int
	}
	
	final def run =
	{
		val result = execute(analyze)
		processingComplete(result.isEmpty)
		result
	}
	private def analyze =
	{
		import scala.collection.mutable.HashSet
		
		val sourcesSnapshot = sourcesToProcess
		val removedSources = new HashSet[Source]
		removedSources ++= analysis.allSources
		removedSources --= sourcesSnapshot
		val removedCount = removedSources.size
		for(removed <- removedSources)
			analysis.removeDependent(removed)
		
		val unmodified = new HashSet[Source]
		val modified = new HashSet[Source]
		
		for(source <- sourcesSnapshot)
		{
			if(isSourceModified(source))
			{
				log.debug("Source " + source + " directly modified.")
				modified += source
			}
			else
			{
				log.debug("Source " + source + " unmodified.")
				unmodified += source
			}
		}
		val directlyModifiedCount = modified.size
		for((external, info) <- externalInfo(analysis.allExternals))
		{
			val dependentSources = analysis.externalDependencies(external).get
			if(info.available)
			{
				val dependencyLastModified = info.lastModified
				for(dependentSource <- dependentSources; dependentProducts <- analysis.products(dependentSource))
				{
					dependentProducts.find(p => productLastModified(p) < dependencyLastModified) match
					{
						case Some(modifiedProduct) =>
						{
							log.debug(productType + " " + modifiedProduct + " older than external dependency " + external)
							unmodified -= dependentSource
							modified += dependentSource
						}
						case None => ()
					}
				}
			}
			else
			{
				log.debug("External dependency " + external + " not found.")
				unmodified --= dependentSources
				modified ++= dependentSources
				analysis.removeExternalDependency(external)
			}
		}
		
		val handled = new scala.collection.mutable.HashSet[Source]
		def markModified(changed: Iterable[Source]) { for(c <- changed if !handled.contains(c)) markSourceModified(c) }
		def markSourceModified(src: Source)
		{
			unmodified -= src
			modified += src
			handled += src
			markDependenciesModified(src)
		}
		def markDependenciesModified(src: Source) { analysis.removeDependencies(src).map(markModified) }

		markModified(modified.toList)
		removedSources.foreach(markDependenciesModified)
		
		for(changed <- removedSources ++ modified)
			analysis.removeSource(changed)
		
		new ConditionalAnalysis
		{
			def dirtySources = modified.readOnly
			def cleanSources = unmodified.readOnly
			def directlyModifiedSourcesCount = directlyModifiedCount
			def invalidatedSourcesCount = dirtySources.size - directlyModifiedCount
			def removedSourcesCount = removedCount
			override def toString =
			{
				"  Source analysis: " + directlyModifiedSourcesCount + " new/modifed, " +
					invalidatedSourcesCount + " indirectly invalidated, " +
					removedSourcesCount + " removed."
			}
		}
	}
	
	private def isSourceModified(source: Source) =
	{
		analysis.products(source) match
		{
			case None =>
			{
				log.debug("New file " + source)
				true
			}
			case Some(sourceProducts) =>
			{
				val sourceModificationTime = sourceLastModified(source)
				def isUpdated(p: Product) = !productExists(p) || productLastModified(p) < sourceModificationTime
					
				val modified = sourceProducts.find(isUpdated)
				if(modified.isDefined)
					log.debug("Modified " + productType + ": " + modified.get)
				modified.isDefined
			}
		}
	}
	protected def processingComplete(success: Boolean)
	{
		if(success)
		{
			analysis.save()
			log.info("  Post-analysis: " + analysis.allProducts.toSeq.length + " " + productTypePlural + ".")
		}
		else
			analysis.revert()
	}
}

abstract class CompileConfiguration extends NotNull
{
	def label: String
	def sources: PathFinder
	def outputDirectory: Path
	def classpath: PathFinder
	def analysisPath: Path
	def projectPath: Path
	def log: Logger
	def options: Seq[String]
	def testDefinitionClassNames: Iterable[String]
}
import java.io.File
class CompileConditional(config: CompileConfiguration) extends Conditional[Path, Path, File]
{
	import config._
	type AnalysisType = CompileAnalysis
	protected def loadAnalysis =
	{
		val a = new CompileAnalysis(analysisPath, projectPath, log)
		for(errorMessage <- a.load())
			error(errorMessage)
		a
	}
	
	protected def log = config.log
	
	protected def productType = "class"
	protected def productTypePlural = "classes"
	protected def sourcesToProcess = sources.get
	
	protected def sourceExists(source: Path) = source.asFile.exists
	protected def sourceLastModified(source: Path) = source.asFile.lastModified
	
	protected def productExists(product: Path) = product.asFile.exists
	protected def productLastModified(product: Path) = product.asFile.lastModified
	
	protected def externalInfo(externals: Iterable[File]) =
	{
		val (classpathJars, classpathDirs) = ClasspathUtilities.buildSearchPaths(classpath.get)
		log.debug("Search path jars: " + classpathJars.mkString(File.pathSeparator))
		log.debug("Search path directories: " + classpathDirs.mkString(File.pathSeparator))
		for(external <- externals) yield
		{
			val available = external.exists && ClasspathUtilities.onClasspath(classpathJars, classpathDirs, external)
			if(!available)
				log.debug("External " + external + (if(external.exists) " not on classpath." else " does not exist."))
			(external, ExternalInfo(available, external.lastModified))
		}
	}
	
	protected def execute(executeAnalysis: ConditionalAnalysis) =
	{
		log.info(executeAnalysis.toString)
		import executeAnalysis.dirtySources
		val cp = classpath.get
		if(!dirtySources.isEmpty)
			checkClasspath(cp)
		val classpathString = Path.makeString(cp)
		val id = AnalysisCallback.register(analysisCallback)
		val allOptions = (("-Xplugin:" + FileUtilities.sbtJar.getCanonicalPath) ::
			("-P:sbt-analyzer:callback:" + id.toString) :: Nil) ++ options
		val r = Compile(label, dirtySources, classpathString, outputDirectory, allOptions, log)
		AnalysisCallback.unregister(id)
		if(log.atLevel(Level.Debug))
		{
			/** This checks that the plugin accounted for all classes in the output directory.*/
			val classes = scala.collection.mutable.HashSet(analysis.allProducts.toSeq: _*)
			var missed = 0
			for(c <- (outputDirectory ** GlobFilter("*.class")).get)
			{
				if(!classes.contains(c))
				{
					missed += 1
					log.debug("Missed class: " + c)
				}
			}
			log.debug("Total missed classes: " + missed)
		}
		r
	}
	private def checkClasspath(cp: Iterable[Path])
	{
		import scala.collection.mutable.{HashMap, HashSet, Set}
		val collisions = new HashMap[String, Set[Path]]
		for(jar <- cp if ClasspathUtilities.isArchive(jar))
			collisions.getOrElseUpdate(jar.asFile.getName, new HashSet[Path]) += jar
		for((name, jars) <- collisions)
		{
			if(jars.size > 1)
			{
				log.warn("Possible duplicate classpath locations for jar " + name + ": ")
				for(jar <- jars) log.warn("\t" + jar.asFile.getCanonicalPath)
			}
		}
	}
	
	protected def analysisCallback: AnalysisCallback =
		new BasicAnalysisCallback(projectPath, testDefinitionClassNames, analysis)
		{
			def foundSubclass(sourcePath: Path, subclassName: String, superclassName: String, isModule: Boolean)
			{
				analysis.addTest(sourcePath, TestDefinition(isModule, subclassName, superclassName))
			}
		}
}