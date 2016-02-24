name := "constructr-machine"

libraryDependencies ++= Vector(
  Library.akkaActor,
  Library.akkaTestkit % "test",
  Library.scalaMock   % "test",
  Library.scalaTest   % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.machine._""".stripMargin
