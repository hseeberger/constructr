libraryDependencies ++= Vector(
  Library.akkaCluster,
  Library.akkaLog4j            % "test",
  Library.akkaMultiNodeTestkit % "test",
  Library.akkaTestkit          % "test",
  Library.log4jCore            % "test",
  Library.scalaMock            % "test",
  Library.scalaTest            % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.akka._""".stripMargin

unmanagedSourceDirectories.in(MultiJvm) := Vector(scalaSource.in(MultiJvm).value)

test.in(Test) := { test.in(MultiJvm).value; test.in(Test).value }

inConfig(MultiJvm)(reformatOnCompileSettings)
inConfig(MultiJvm)(compileInputs.in(compile) := { scalafmt.value; compileInputs.in(compile).value })

AutomateHeaderPlugin.automateFor(Compile, Test, MultiJvm)
HeaderPlugin.settingsFor(Compile, Test, MultiJvm)
