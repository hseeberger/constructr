name := "constructr-coordination"

libraryDependencies ++= List(
  Library.akkaActor,
  Library.akkaHttp,
  Library.raptureJsonSpray,
  Library.akkaLog4j   % "test",
  Library.akkaTestkit % "test",
  Library.log4jCore   % "test",
  Library.scalaCheck  % "test",
  Library.scalaTest   % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.coordination._""".stripMargin
