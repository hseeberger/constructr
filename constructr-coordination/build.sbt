name := "constructr-coordination"

libraryDependencies ++= List(
  Library.akkaHttp,
  Library.commonsCodec, // TODO Only needed on Java 7!
  Library.raptureJsonSpray,
  Library.akkaTestkit % "test",
  Library.scalaTest   % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.coordination._""".stripMargin
