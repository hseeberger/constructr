name := "constructr-coordination-etcd"

libraryDependencies ++= Vector(
  Library.akkaHttp,
  Library.raptureJsonCirce,
  Library.akkaTestkit % "test",
  Library.scalaTest   % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.coordination.etcd._""".stripMargin
