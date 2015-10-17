val constructr = project
  .in(file("."))
  .configs(MultiJvm)
  .enablePlugins(AutomateHeaderPlugin, GitVersioning)

organization := "de.heikoseeberger"
name         := "constructr"
licenses     += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

homepage             := Some(url("https://github.com/hseeberger/constructr"))
pomIncludeRepository := (_ => false)
pomExtra             := <scm>
                          <url>https://github.com/hseeberger/constructr</url>
                          <connection>scm:git:git@github.com:hseeberger/constructr.git</connection>
                        </scm>
                        <developers>
                          <developer>
                            <id>hseeberger</id>
                            <name>Heiko Seeberger</name>
                            <url>http://heikoseeberger.de</url>
                          </developer>
                        </developers>

scalaVersion   := "2.11.7"
scalacOptions ++= List(
  "-unchecked",
  "-deprecation",
  "-language:_",
  "-target:jvm-1.8",
  "-encoding", "UTF-8"
)

unmanagedSourceDirectories.in(Compile)  := List(scalaSource.in(Compile).value)
unmanagedSourceDirectories.in(Test)     := List(scalaSource.in(Test).value)
unmanagedSourceDirectories.in(MultiJvm) := List(scalaSource.in(MultiJvm).value)

val akkaVersion       = "2.4.0"
val akkaHttpVersion   = "1.0"
libraryDependencies ++= List(
  "com.propensive"           %% "rapture-json-spray"                % "1.1.0",
  "com.typesafe.akka"        %% "akka-cluster"                      % akkaVersion,
  "com.typesafe.akka"        %% "akka-http-experimental"            % akkaHttpVersion,
  "com.typesafe.akka"        %% "akka-http-spray-json-experimental" % akkaHttpVersion,
  "de.heikoseeberger"        %% "akka-log4j"                        % "1.0.1",
  "io.spray"                 %% "spray-json"                        % "1.3.2",
  "com.typesafe.akka"        %% "akka-http-testkit-experimental"    % akkaHttpVersion % "test",
  "com.typesafe.akka"        %% "akka-multi-node-testkit"           % akkaVersion     % "test",
  "com.typesafe.akka"        %% "akka-testkit"                      % akkaVersion     % "test",
  "org.apache.logging.log4j" %  "log4j-core"                        % "2.3"           % "test",
  "org.scalacheck"           %% "scalacheck"                        % "1.12.5"        % "test",
  "org.scalatest"            %% "scalatest"                         % "2.2.5"         % "test"
)

initialCommands := """|import de.heikoseeberger.constructr._
                      |import akka.actor._
                      |import akka.http.scaladsl.model._
                      |import scala.concurrent.duration._""".stripMargin

test.in(Test) := { test.in(MultiJvm).value; test.in(Test).value }

git.baseVersion := "0.2.0"

import scalariform.formatter.preferences._
preferences := preferences.value
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 100)
  .setPreference(DoubleIndentClassDeclaration, true)
inConfig(MultiJvm)(SbtScalariform.configScalariformSettings)
inConfig(MultiJvm)(compileInputs.in(compile) := { format.value; compileInputs.in(compile).value })

headers := Map("scala" -> de.heikoseeberger.sbtheader.license.Apache2_0("2015", "Heiko Seeberger"))
AutomateHeaderPlugin.automateFor(Compile, Test, MultiJvm)
HeaderPlugin.settingsFor(Compile, Test, MultiJvm)
