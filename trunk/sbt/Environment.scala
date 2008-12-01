/* sbt -- Simple Build Tool
 * Copyright 2008 Mark Harrah, David MacIver
 */
package sbt

import scala.reflect.Manifest

trait Environment
{
	abstract class Property[T] extends NotNull
	{
		/** Explicitly sets the value of this property to 'v'.*/
		def update(v: T): Unit
		/** Returns the current value of this property or throws an exception if the value could not be obtained.*/
		def value: T = resolve.value
		/** Returns the current value of this property in an 'Option'.  'None' is used to indicate that the
		* value could not obtained.*/
		def get: Option[T] = resolve.toOption
		/** Returns full information about this property's current value. */
		def resolve: PropertyResolution[T]
		
		def foreach(f: T => Unit): Unit = resolve.foreach(f)
	}
	
	/** Creates a system property with the given name and no default value.*/
	def system[T](propName: String)(implicit format: Format[T]): Property[T]
	/** Creates a system property with the given name and the given default value to use if no value is explicitly specified.*/
	def systemOptional[T](propName: String, defaultValue: => T)(implicit format: Format[T]): Property[T]
	/** Creates a user-defined property that has no default value.  The property will try to inherit its value
	* from a parent environment (if one exists) if its value is not explicitly specified.  An explicitly specified
	* value will persist between builds if the object returned by this method is assigned to a 'val' in this
	* 'Environment'.*/
	def property[T](implicit manifest: Manifest[T], format: Format[T]): Property[T]
	/** Creates a user-defined property that has no default value.  The property will try to inherit its value
	* from a parent environment (if one exists) if its value is not explicitly specified.  An explicitly specified
	* value will persist between builds if the object returned by this method is assigned to a 'val' in this
	* 'Environment'.  The given 'format' is used to convert an instance of 'T' to and from the 'String' representation
	* used for persistence.*/
	def propertyF[T](format: Format[T])(implicit manifest: Manifest[T]): Property[T] = property(manifest, format)
	/** Creates a user-defined property with no default value and no value inheritance from a parent environment.
	* Its value will persist between builds if the returned object is assigned to a 'val' in this 'Environment'.*/
	def propertyLocal[T](implicit manifest: Manifest[T], format: Format[T]): Property[T]
	/** Creates a user-defined property with no default value and no value inheritance from a parent environment.
	* The property's value will persist between builds if the object returned by this method is assigned to a
	* 'val' in this 'Environment'.  The given 'format' is used to convert an instance of 'T' to and from the
	* 'String' representation used for persistence.*/
	def propertyLocalF[T](format: Format[T])(implicit manifest: Manifest[T]): Property[T] = propertyLocal(manifest, format)
	/** Creates a user-defined property that uses the given default value if no value is explicitly specified for this property.  The property's value will persist between builds
	* if the object returned by this method is assigned to a 'val' in this 'Environment'.*/
	def propertyOptional[T](defaultValue: => T)(implicit manifest: Manifest[T], format: Format[T]): Property[T]
	/** Creates a user-defined property with no value inheritance from a parent environment but with the given default
	* value if no value is explicitly specified for this property.  The property's value will persist between builds
	* if the object returned by this method is assigned to a 'val' in this 'Environment'.  The given 'format' is used
	* to convert an instance of 'T' to and from the 'String' representation used for persistence.*/
	def propertyOptionalF[T](defaultValue: => T, format: Format[T])(implicit manifest: Manifest[T]): Property[T] =
		propertyOptional(defaultValue)(manifest, format)
}
trait Format[T] extends NotNull
{
	def toString(t: T): String
	def fromString(s: String): T
}
abstract class SimpleFormat[T] extends Format[T]
{
	def toString(t: T) = t.toString
}

import scala.collection.Map
trait BasicEnvironment extends Environment
{
	protected def log: Logger
	/** The location of the properties file that backs the user-defined properties. */
	def envBackingPath: Path
	/** The environment from which user-defined properties inherit (if enabled). */
	protected def parentEnvironment: Option[BasicEnvironment] = None
	/** The identifier used in messages to refer to this environment. */
	def environmentLabel = envBackingPath.asFile.getCanonicalPath
	
	/** Implementation of 'Property' for user-defined properties. */
	private[sbt] class UserProperty[T](lazyDefaultValue: => Option[T], format: Format[T], inheritEnabled: Boolean,
		private[BasicEnvironment] val manifest: Manifest[T]) extends Property[T]
	{
		/** The name of this property is used for persistence in the properties file and as an identifier in messages.*/
		lazy val name = variableMap.find( p => p._2 eq this ).map(_._1)
		/** Gets the name of this property or an alternative if the name is not available.*/
		private def nameString = name.getOrElse("<unnamed>")
		/** The lazily evaluated default value for this property.*/
		private lazy val defaultValue = lazyDefaultValue
		/** The explicitly set value for this property.*/
		private var explicitValue: Option[T] = None
		def update(v: T): Unit = synchronized { explicitValue = Some(v) }
		def resolve: PropertyResolution[T] =
			synchronized
			{
				(explicitValue orElse defaultValue) match
				{
					case Some(v) => DefinedValue(v, false, explicitValue.isEmpty)
					case None => inheritedValue
				}
			}
		
		private def inheritedValue: PropertyResolution[T] =
		{
			val propOption = if(inheritEnabled) parentProperty else None
			propOption match
			{
				case Some(prop) => tryToInherit(prop)
				case None => UndefinedValue(nameString, environmentLabel)
			}
		}
		private def parentProperty = for(parent <- parentEnvironment; n <- name; prop <- parent.variableMap.get(n)) yield prop
		
		private def tryToInherit[R](prop: BasicEnvironment#UserProperty[R]): PropertyResolution[T] =
		{
			if(prop.manifest <:< manifest)
				markInherited(prop.resolve.asInstanceOf[PropertyResolution[T]])
			else
				ResolutionException("Could not inherit property '" + nameString + "' from '" + environmentLabel + "':\n" +
					"\t Property had type " + prop.manifest + ", expected type " + manifest, None)
		}
		private def markInherited(result: PropertyResolution[T]) =
			result match
			{
				case DefinedValue(v, isInherited, isDefault) => DefinedValue(v, true, isDefault)
				case x => x
			}
		
		/** Gets the explicitly set value converted to a 'String'.*/
		private[sbt] def getStringValue: Option[String] = explicitValue.map(format.toString)
		/** Explicitly sets the value for this property by converting the given string value.*/
		private[sbt] def setStringValue(s: String) { update(format.fromString(s)) }
	}
	
	private class SystemProperty[T](val name: String, lazyDefaultValue: => Option[T], val format: Format[T]) extends Property[T]
	{
		def resolve =
		{
			val rawValue = System.getProperty(name)
			if(rawValue == null)
				notFound
			else
			{
				Environment.convertException(format.fromString(rawValue)) match
				{
					case Left(e) => ResolutionException("Error parsing system property '" + name + "': " + e.toString, Some(e))
					case Right(x) => DefinedValue(x, false, false)
				}
			}
		}
		private def notFound =
		{
			defaultValue match
			{
				case Some(dv) =>
				{
					log.debug("System property '" + name + "' does not exist, using provided default.")
					DefinedValue(dv, false, true)
				}
				case None => UndefinedValue(name, environmentLabel)
			}
		}
		protected lazy val defaultValue = lazyDefaultValue
		def update(t: T)
		{
			for(e <- Environment.convertException(System.setProperty(name, format.toString(t))).left)
			{
				log.trace(e)
				log.warn("Error setting system property '" + name + "': " + e.toString)
			}
		}
	}
	
	def system[T](propertyName: String)(implicit format: Format[T]): Property[T] =
		new SystemProperty[T](propertyName, None, format)
	def systemOptional[T](propertyName: String, defaultValue: => T)(implicit format: Format[T]): Property[T] =
		new SystemProperty[T](propertyName, Some(defaultValue), format)
	
	def property[T](implicit manifest: Manifest[T], format: Format[T]): Property[T] =
		new UserProperty[T](None, format, true, manifest)
	def propertyLocal[T](implicit manifest: Manifest[T], format: Format[T]): Property[T] =
		new UserProperty[T](None, format, false, manifest)
	def propertyOptional[T](defaultValue: => T)(implicit manifest: Manifest[T], format: Format[T]): Property[T] =
		new UserProperty[T](Some(defaultValue), format, true, manifest)
	
	private type AnyVariable = Property[T] forSome {type T}
	private type AnyProperty = UserProperty[T] forSome {type T}
	private val variableMap = new scala.collection.mutable.HashMap[String, AnyProperty]
	
	import java.util.Properties
	private[sbt] def initializeEnvironment()
	{
		val varMap = Environment.reflectiveMappings(this, classOf[AnyVariable])
		
		val propertyMap = new scala.collection.mutable.HashMap[String, AnyProperty]
		for( (name, property: AnyProperty) <- varMap)
			variableMap(name) = property
		
		val properties = new Properties
		for(errorMsg <- PropertiesUtilities.load(properties, envBackingPath, log))
			log.error("Error loading properties from " + environmentLabel + " : " + errorMsg)
		
		for(name <- PropertiesUtilities.propertyNames(properties))
		{
			val propertyValue = properties.getProperty(name)
			variableMap.get(name) match
			{
				case Some(property) => property.setStringValue(propertyValue)
				case None =>
				{
					val p = new UserProperty[String](None, StringFormat, false, Manifest.classType(classOf[String]))
					p() = propertyValue
					variableMap(name) = p
					log.warn("Property '" + name + "' from " + environmentLabel + " is not used.")
				}
			}
		}
	}
	def propertyNames: Iterable[String] = variableMap.keys.toList
	def getPropertyNamed(name: String): Option[UserProperty[_]] = variableMap.get(name)
	def propertyNamed(name: String): UserProperty[_] = variableMap(name)
	def saveEnvironment(): Option[String] =
	{
		val properties = new Properties
		for( (name, variable) <- variableMap; stringValue <- variable.getStringValue)
			properties.setProperty(name, stringValue)
		PropertiesUtilities.write(properties, "Project properties", envBackingPath, log)
	}
	private[sbt] def uninitializedProperties: Iterable[(String, Property[_])] = variableMap.filter(_._2.get.isEmpty)
	
	
	implicit val IntFormat: Format[Int] = new SimpleFormat[Int] { def fromString(s: String) = java.lang.Integer.parseInt(s) }
	implicit val LongFormat: Format[Long] = new SimpleFormat[Long] { def fromString(s: String) = java.lang.Long.parseLong(s) }
	implicit val DoubleFormat: Format[Double] = new SimpleFormat[Double] { def fromString(s: String) = java.lang.Double.parseDouble(s) }
	implicit val BooleanFormat: Format[Boolean] = new SimpleFormat[Boolean] { def fromString(s: String) = java.lang.Boolean.valueOf(s).booleanValue }
	implicit val StringFormat: Format[String] = new SimpleFormat[String] { def fromString(s: String) = s }
	val NonEmptyStringFormat: Format[String] = new SimpleFormat[String]
	{
		def fromString(s: String) =
		{
			val trimmed = s.trim
			if(trimmed.isEmpty)
				throw new RuntimeException("The empty string is not allowed.")
			else
				trimmed
		}
	}
	implicit val VersionFormat: Format[Version] =
		new SimpleFormat[Version]
		{
			def fromString(s: String) = Version.fromString(s).fold(msg => throw new RuntimeException(msg), x => x)
		}
	import java.io.File
	implicit val FileFormat: Format[File] =
		new Format[File]
		{
			def fromString(s: String) = (new File(s)).getCanonicalFile
			def toString(f: File) = f.getCanonicalPath
		}
}
private object Environment
{
	def convertException[T](t: => T): Either[Throwable, T] =
	{
		try { Right(t) }
		catch { case e => Left(e) }
	}
	def reflectiveMappings[T](obj: AnyRef, clazz: Class[T]): Map[String, T] =
	{
		val mappings = new scala.collection.mutable.OpenHashMap[String, T]
		for ((name, value) <- ReflectUtilities.allValsC(obj, clazz))
			mappings(ReflectUtilities.transformCamelCase(name, '.')) = value
		mappings
	}
}

sealed trait PropertyResolution[+T] extends NotNull
{
	def value: T
	def orElse[R >: T](r: => PropertyResolution[R]): PropertyResolution[R]
	def toOption: Option[T]
	def foreach(f: T => Unit): Unit
	def map[R](f: T => R): PropertyResolution[R]
	def flatMap[R](f: T => PropertyResolution[R]): PropertyResolution[R]
}
sealed trait NoPropertyValue extends PropertyResolution[Nothing]
{ self: RuntimeException with PropertyResolution[Nothing] =>

	def value = throw this
	def toOption = None
	def map[R](f: Nothing => R): PropertyResolution[R] = this
	def flatMap[R](f: Nothing => PropertyResolution[R]): PropertyResolution[R] = this
	def foreach(f: Nothing => Unit) {}
}
final case class ResolutionException(message: String, exception: Option[Throwable])
	extends RuntimeException(message, exception.getOrElse(null)) with NoPropertyValue
{
	def orElse[R](r: => PropertyResolution[R]) = this
}
final case class UndefinedValue(name: String, environmentLabel: String)
	extends RuntimeException("Value for property '" + name + "' from " + environmentLabel + " is undefined.") with NoPropertyValue
{
	def orElse[R](r: => PropertyResolution[R]) =
		r match
		{
			case u: UndefinedValue => this
			case _ => r
		}
}
final case class DefinedValue[T](value: T, isInherited: Boolean, isDefault: Boolean) extends PropertyResolution[T]
{
	def toOption = Some(value)
	def orElse[R >: T](r: => PropertyResolution[R]) = this
	def map[R](f: T => R) = DefinedValue[R](f(value), isInherited, isDefault)
	def flatMap[R](f: T => PropertyResolution[R]) = f(value)
	def foreach(f: T => Unit) { f(value) }
}