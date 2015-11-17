name := "constructr-machine"

libraryDependencies ++= List(
  Library.akkaActor
)

initialCommands := """|import de.heikoseeberger.constructr.machine._""".stripMargin