import bintray.BintrayPlugin
import com.typesafe.sbt.{GitPlugin, SbtScalariform}
import de.heikoseeberger.sbtheader.HeaderPlugin
import de.heikoseeberger.sbtheader.license.Apache2_0
import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin
import scalariform.formatter.preferences.{AlignSingleLineCaseStatements, DoubleIndentClassDeclaration}

object Build extends AutoPlugin {

  override def requires = JvmPlugin && HeaderPlugin && GitPlugin && SbtScalariform && BintrayPlugin

  override def trigger = allRequirements

  override def projectSettings = Vector(
    // Core settings
    organization := "de.heikoseeberger",
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    homepage := Some(url("https://github.com/hseeberger/constructr")),
    pomIncludeRepository := (_ => false),
    pomExtra := <scm>
                  <url>https://github.com/hseeberger/constructr</url>
                  <connection>scm:git:git@github.com:hseeberger/constructr.git</connection>
                </scm>
                <developers>
                  <developer>
                    <id>hseeberger</id>
                    <name>Heiko Seeberger</name>
                    <url>http://heikoseeberger.de</url>
                  </developer>
                  <developer>
                    <id>everpeace</id>
                    <name>Shingo Omura</name>
                    <url>https://everpeace.github.io</url>
                  </developer>
                </developers>,
    scalaVersion := Version.Scala,
    crossScalaVersions := Vector(scalaVersion.value),
    scalacOptions ++= Vector(
      "-unchecked",
      "-deprecation",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding", "UTF-8"
    ),
    unmanagedSourceDirectories.in(Compile) := Vector(scalaSource.in(Compile).value),
    unmanagedSourceDirectories.in(Test) := Vector(scalaSource.in(Test).value),

    // Scalariform settings
    SbtScalariform.autoImport.scalariformPreferences := SbtScalariform.autoImport.scalariformPreferences.value
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 100)
      .setPreference(DoubleIndentClassDeclaration, true),

    // Git settings
    GitPlugin.autoImport.git.useGitDescribe := true,

    // Header settings
    HeaderPlugin.autoImport.headers := Map("scala" -> Apache2_0("2015", "Heiko Seeberger")),

    // Bintray settings
    BintrayPlugin.autoImport.bintrayPackage := "constructr"
  )
}
