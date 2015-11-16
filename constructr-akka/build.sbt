name := "constructr-akka"

libraryDependencies ++= List(
  Library.akkaCluster,
  Library.akkaHttp,
  Library.akkaHttpSprayJson,
  Library.raptureJsonSpray,
  Library.akkaLog4j            % "test",
  Library.akkaMultiNodeTestkit % "test",
  Library.akkaTestkit          % "test",
  Library.log4jCore            % "test",
  Library.scalaCheck           % "test",
  Library.scalaTest            % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.akka._""".stripMargin

unmanagedSourceDirectories.in(MultiJvm) := List(scalaSource.in(MultiJvm).value)

test.in(Test) := { test.in(MultiJvm).value; test.in(Test).value }

inConfig(MultiJvm)(SbtScalariform.configScalariformSettings)
inConfig(MultiJvm)(compileInputs.in(compile) := { format.value; compileInputs.in(compile).value })

AutomateHeaderPlugin.automateFor(Compile, Test, MultiJvm)
HeaderPlugin.settingsFor(Compile, Test, MultiJvm)
