name := "constructr-cassandra"

libraryDependencies ++= List(
  Library.akkaActor,
  Library.cassandraAll,
  Library.akkaLog4j            % "test",
  Library.akkaTestkit          % "test",
  Library.log4jCore            % "test",
  Library.scalaCheck           % "test",
  Library.scalaTest            % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.cassandra._""".stripMargin

assemblyMergeStrategy.in(assembly) := {
  case "LICENSE" => MergeStrategy.concat
  case other     => assemblyMergeStrategy.in(assembly).value(other)
}
