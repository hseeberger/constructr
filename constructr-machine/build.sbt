name := "constructr-machine"

libraryDependencies ++= List(
  Library.akkaActor,
  Library.akkaTestkit % "test",
  Library.scalaTest   % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.machine._""".stripMargin
