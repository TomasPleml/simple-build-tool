import sbt._

class TestProject(info: ProjectInfo) extends DefaultProject(info)
{
	val sc = "org.scala-tools.testing" % "scalacheck" % "1.5" % "test->default"
}