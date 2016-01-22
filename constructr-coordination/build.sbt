name := "constructr-coordination"

libraryDependencies ++= List(
  Library.akkaActor,
  Library.akkaHttp,
  Library.raptureJsonSpray,
  Library.akkaTestkit % "test",
  Library.scalaTest   % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.coordination._""".stripMargin
