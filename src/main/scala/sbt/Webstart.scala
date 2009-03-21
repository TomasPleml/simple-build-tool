/* sbt -- Simple Build Tool
 * Copyright 2009 Mark Harrah
 */
package sbt

import java.io.File
import scala.xml.{Elem, NodeSeq}
import Control._

/** Defines the configurable parameters for the webstart task. */
trait WebstartOptions extends NotNull
{
	/** The main jar to use for webstart.*/
	def webstartMainJar: Path
	/** The location to put all generated files for webstart.*/
	def webstartOutputDirectory: Path
	/** Generates the .jnlp file using the provided resource descriptions.  Each resource description
	* provides the path of the jar relative to 'webstartOutputDirectory' and whether or not
	* it is the main jar.*/
	def jnlpXML(jars: Seq[WebstartJarResource]): Elem
	/** The location to write the .jnlp file to.  It must be in 'webstartOutputDirectory'.*/
	def jnlpFile: Path
	/** The location to put all jars that are not the main jar.  It must be in 'webstartOutputDirectory'.*/
	def webstartLibDirectory: Path
	/** The libraries needed for webstart.  Note that only jars are used; directories are discarded.*/
	def webstartLibraries: PathFinder
	/** Libraries external to the project needed for webstart.  This is mainly for scala libraries.*/
	def webstartExtraLibraries: Iterable[File]
	/** Resources to copy to the webstart output directory.*/
	def webstartResources: PathFinder
	/** If defined, this specifies where to create a zip of the webstart output directory.  It cannot be
	* in the output directory.*/
	def webstartZip: Option[Path]

	/** If defined, configures signing of jars.  All jars (main and libraries) are signed using
	* this configuration.*/
	def webstartSignConfiguration: Option[SignConfiguration]
	/** If true, pack200 compression is applied to all jars (main and libraries).  A version of each jar
	* without pack200 compression is still created in the webstart output directory.*/
	def webstartPack200: Boolean
	/** If true, gzip compression will be applied to all jars.  If pack200 compression is enabled,
	* gzip compression is also applied to the archives with pack200 compression.  A version of
	* each file without gzip compression is still created in the webstart output directory. */
	def webstartGzip: Boolean
}
/** Represents a library included in the webstart distribution.  Name is the filename of the jar.
* href is the path of the jar relative to the webstart output directory.  isMain is true only for
* the main jar. */
final class WebstartJarResource(val name: String, val href: String, val isMain: Boolean) extends NotNull
/** Configuration for signing jars. */
final class SignConfiguration(val alias: String, val options: Seq[SignJar.SignOption]) extends NotNull
/** A scala project that produces a webstart distribution. */
trait WebstartScalaProject extends ScalaProject
{
	import WebstartScalaProject._
	/** Creates a task that produces a webstart distribution using the given options.*/
	def webstartTask(options: WebstartOptions) =
		task
		{
			import options._
			FileUtilities.createDirectories(webstartOutputDirectory :: webstartLibDirectory :: Nil, log) // ignore errors
			verifyOptions(options)
			
			def relativize(jar: Path) = Path.relativize(webstartOutputDirectory ##, jar) getOrElse
				error("Jar (" + jar + ") was not in webstart output directory (" + webstartOutputDirectory + ").")
			def signAndPack(jars: List[Path], targetDirectory: Path): Either[String, List[Path]] =
			{
				lazyFold(jars, Nil: List[Path])
				{ (allJars, jar) =>
					val signPackResult =
						webstartSignConfiguration match
						{
							case Some(config) =>
								if(webstartPack200)
									signAndPack200(jar, config, targetDirectory, log)
								else
									signOnly(jar, config, targetDirectory, log)
							case None =>
								if(webstartPack200)
									pack200Only(jar, targetDirectory, log)
								else
									copyJar(jar, targetDirectory, log)
						}
					signPackResult.right flatMap { addJars =>
						if(webstartGzip)
							Control.lazyFold(addJars, addJars ::: allJars)
								{ (accumulate, jar) => gzipJar(jar, log).right.map(_ ::: accumulate) }
						else
							Right(addJars ::: allJars)
					}
				}
			}
		
			import FileUtilities._
			
			def fileToPath(file: File): Path = new ProjectDirectory(file) // hack, don't do this normally
			val jars = (webstartLibraries.get ++ webstartExtraLibraries.map(fileToPath)).filter(ClasspathUtilities.isArchive)
			def process(jars: Iterable[Path]) = for(jar <- jars if jar.asFile.getName.endsWith(".jar")) yield relativize(jar)
			
			thread(signAndPack(webstartMainJar :: Nil, webstartOutputDirectory)) { mainJars =>
				thread(signAndPack(jars.toList, webstartLibDirectory)) { libJars =>
					writeXML(jnlpXML(jarResources(process(mainJars), process(libJars))), jnlpFile, log) orElse
					thread(copy(webstartResources.get, webstartOutputDirectory, log)) { copiedResources =>
						val keep = jnlpFile +++ Path.lazyPathFinder(mainJars ++ libJars ++ copiedResources) +++
							webstartOutputDirectory +++ webstartLibDirectory
						prune(webstartOutputDirectory, keep.get, log) orElse
						webstartZip.flatMap( zipPath => zip(List(webstartOutputDirectory ##), zipPath, true, log) )
					}
				}
			}
		}
	/** Creates default XML elements for a JNLP file for the given resources.*/
	protected def defaultElements(resources: Seq[WebstartJarResource]): NodeSeq = resources.map(defaultElement)
	/** Creates a default XML element for a JNLP file for the given resource.*/
	protected def defaultElement(resource: WebstartJarResource): Elem =
		<jar href={resource.href} main={resource.isMain.toString}/>
		
}
private object WebstartScalaProject
{
	import FileTasks.{runOption, wrapProduct, wrapProducts}
	/** Changes the extension of the Path of the given jar from ".jar" to newExtension.  If append is true,
	* the new extension is simply appended to the jar's filename. */
	private def appendExtension(jar: Path, newExtension: String) =
		jar match
		{
			case rp: RelativePath => rp.parentPath / (rp.component + newExtension)
			case x => x
		}
	private def gzipJarPath(jar: Path) = appendExtension(jar, ".gz")
	private def packPath(jar: Path) = appendExtension(jar, ".pack")
	private def signOnly(jar: Path, signConfiguration: SignConfiguration, targetDirectory: Path, log: Logger) =
	{
		val targetJar = targetDirectory / jar.asFile.getName
		runOption("sign", targetJar from jar, log) {
			log.debug("Signing " + jar)
			signAndVerify(jar, signConfiguration, targetJar, log)
		}.toLeft(targetJar :: Nil)
	}
	private def signAndVerify(jar: Path, signConfiguration: SignConfiguration, targetJar: Path, log: Logger) =
	{
		import SignJar._
		sign(jar, signConfiguration.alias, signedJar(targetJar) :: signConfiguration.options.toList, log) orElse
			verify(jar, signConfiguration.options, log).map(err => "Signed jar failed verification: " + err)
	}
	private def gzipJar(jar: Path, log: Logger) =
	{
		val gzipJar = gzipJarPath(jar)
		runOption("gzip", gzipJar from jar, log)
		{
			log.debug("Gzipping " + jar)
			FileUtilities.gzip(jar, gzipJar, log)
		}.toLeft(gzipJar :: Nil)
	}
	/** Properly performs both signing and pack200 compression and verifies the result.  This method only does anything if
	* its outputs are out of date with respect to 'jar'.  Note that it does not determine if the signing configuration has changed.
	* See java.util.jar.Pack200 for more information.*/
	private def signAndPack200(jar: Path, signConfiguration: SignConfiguration, targetDirectory: Path, log: Logger) =
	{
		val signedJar = targetDirectory / jar.asFile.getName
		val packedJar = packPath(signedJar)
		import signConfiguration._
		
		runOption("sign and pack200", List(packedJar, signedJar) from jar, log) {
			log.debug("Applying pack200 compression and signing " + jar)
			signAndPack(jar, signedJar, packedJar, alias, options, log) orElse
			signAndVerify(jar, signConfiguration, signedJar, log)
		}.toLeft(packedJar :: signedJar :: Nil)
	}
	/** Properly performs both signing and pack200 compression and verifies the result.  See java.util.jar.Pack200 for more information.*/
	private def signAndPack(jarPath: Path, signedPath: Path, out: Path, alias: String, options: Seq[SignJar.SignOption], log: Logger): Option[String] =
	{
		import Pack._
		import SignJar._
		pack(jarPath, out, log) orElse
		unpack(out, signedPath, log) orElse
		sign(signedPath, alias, options, log) orElse
		pack(signedPath, out, log) orElse
		unpack(out, signedPath, log) orElse
		verify(signedPath, options, log)
	}
	private def pack200Only(jar: Path, targetDirectory: Path, log: Logger) =
	{
		val targetJar = targetDirectory / jar.asFile.getName
		val packedJar = packPath(targetJar)
		val packResult =
			runOption("pack200", packedJar from jar, log)
			{
				log.debug("Applying pack200 compression to " + jar)
				Pack.pack(jar, packedJar, log)
			}
		packResult match
		{
			case Some(err) => Left(err)
			case None => copyJar(jar, targetDirectory, log).right.map(jars => packedJar :: jars)
		}
	}
	private def copyJar(jar: Path, targetDirectory: Path, log: Logger) =
	{
		val targetJar = targetDirectory / jar.asFile.getName
		runOption("copy jar", targetJar from jar, log)( FileUtilities.copyFile(jar, targetJar, log) ).toLeft(targetJar :: Nil)
	}
	/** Writes the XML string 'xmlString' to the file 'outputPath'.*/
	private def writeXML(xmlString: String, outputPath: Path, log: Logger): Option[String] =
		FileUtilities.write(outputPath.asFile, xmlString, log)
	/** Writes the XML string 'xmlString' to the file 'outputPath' if the hashes are different.*/
	private def writeXML(xml: Elem, outputPath: Path, log: Logger): Option[String] =
	{
		val xmlString = scala.xml.Utility.toXML(xml, false)
		if(!outputPath.exists)
		{
			log.debug("JNLP file did not exist, writing inline XML to " + outputPath)
			writeXML(xmlString, outputPath, log)
		}
		else
		{
			val result =
				for( xmlHash <- Hash(xmlString, log).right; fileHash <- Hash(outputPath, log).right ) yield
				{
					if(xmlHash deepEquals fileHash)
					{
						log.debug("JNLP file " + outputPath + " uptodate.")
						None
					}
					else
					{
						log.debug("Inline JNLP XML modified, updating file " + outputPath + ".")
						writeXML(xmlString, outputPath, log)
					}
				}
			result.fold(err => Some(err), x => x)
		}
	}
	private def jarResource(isMain: Boolean)(jar: Path): WebstartJarResource =
		new WebstartJarResource(jar.asFile.getName, jar.relativePath, isMain)
	private def jarResources(mainJars: Iterable[Path], libraries: Iterable[Path]): Seq[WebstartJarResource] =
		mainJars.map(jarResource(true)).toList ::: libraries.map(jarResource(false)).toList
	
	/** True iff 'directory' is an ancestor (strictly) of 'check'.*/
	private def isInDirectory(directory: Path, check: Path) = Path.relativize(directory, check).isDefined && directory != check
	/** Checks the paths in the given options for validity.  See the documentation for WebstartOptions.*/
	private def verifyOptions(options: WebstartOptions)
	{
		import options._
		require(isInDirectory(webstartOutputDirectory, webstartLibDirectory), 
			"Webstart dependency directory (" + webstartLibDirectory + ") must be a subdirectory of webstart output directory (" +
				webstartOutputDirectory + ").")
		require(isInDirectory(webstartOutputDirectory, jnlpFile), "Webstart JNLP file output location (" + jnlpFile +
			") must be in the webstart output directory (" + webstartOutputDirectory + ").")
		for(wz <- webstartZip)
			require(!isInDirectory(webstartOutputDirectory, wz),
				"Webstart output zip location (" + wz + " cannot be in webstart output directory (" + webstartOutputDirectory + ").")
	}
}
/** The default extension point for a webstart project.  There is one method that is required to be defined: jnlpXML.
* 'webstartSignConfiguration', 'webstartPack200', and 'webstartGzip' are methods of interest. */
abstract class DefaultWebstartProject(val info: ProjectInfo) extends BasicWebstartProject
/** Defines default implementations of all methods in WebstartOptions except for jnlpXML.  packageAction is overridden
* to create a webstart distribution after the normal package operation. */
abstract class BasicWebstartProject extends BasicScalaProject with WebstartScalaProject with WebstartPaths
{
	def webstartSignConfiguration: Option[SignConfiguration] = None
	
	def webstartExtraLibraries = scalaJars
	def webstartLibraries = runClasspath +++ jarsOfProjectDependencies
	def webstartResources = descendents(jnlpResourcesPath ##, AllPassFilter)

	def webstartPack200 = true
	def webstartGzip = true
	
	override def packageAction = super.packageAction && webstartTask(this)
}
/** Defines default paths for a webstart project.  It directly extends WebstartOptions to make
* it easy to implement and override webstart options in the common case of one webstartTask per
* project.*/
trait WebstartPaths extends BasicProjectPaths with WebstartOptions
{
	def webstartOutputDirectory = outputPath / webstartDirectoryName
	def webstartMainJar = outputPath / defaultJarName
	
	def webstartLibDirectory = webstartOutputDirectory / webstartLibName
	def jnlpFile = webstartOutputDirectory / jnlpFileName
	def webstartZip: Option[Path] = Some(outputPath / webstartZipName)
	def jnlpPath = mainSourcePath / DefaultJnlpName
	def jnlpResourcesPath = jnlpPath / BasicProjectPaths.DefaultResourcesDirectoryName
	
	def jnlpFileName = DefaultJnlpFileName
	def webstartLibName = DefaultWebstartLibName
	def webstartDirectoryName = DefaultWebstartDirectoryName
	def webstartZipName = defaultJarBaseName + ".zip"
	
	val DefaultWebstartDirectoryName = "webstart"
	val DefaultJnlpName = "jnlp"
	def DefaultJnlpFileName = defaultJarBaseName + ".jnlp"
	val DefaultWebstartLibName = "lib"
}