name := "constructr-coordination"

libraryDependencies ++= Vector(
  Library.akkaHttp,
  Library.raptureJsonSpray,
  Library.akkaTestkit % "test",
  Library.scalaTest   % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.coordination._""".stripMargin
