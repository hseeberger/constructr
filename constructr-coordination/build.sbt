libraryDependencies ++= Vector(
  Library.akkaHttp,
  Library.akkaTestkit % "test",
  Library.scalaTest   % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.coordination._""".stripMargin
