name := "constructr-cassandra"

libraryDependencies ++= List(
  Library.akkaActor,
  Library.akkaSlf4j,
  Library.cassandraAll % "provided",
  Library.akkaTestkit  % "test",
  Library.scalaTest    % "test"
)

initialCommands := """|import de.heikoseeberger.constructr.cassandra._""".stripMargin

publishArtifact := false

assemblyMergeStrategy.in(assembly) := {
  case "LICENSE" => MergeStrategy.concat
  case other     => assemblyMergeStrategy.in(assembly).value(other)
}

docker                := docker.dependsOn(assembly).value
imageNames.in(docker) := List(ImageName(s"constructr/cassandra:2.2"))
dockerfile.in(docker) := {
  val fatJar = assemblyOutputPath.in(assembly).value
  val fatJarTargetPath = s"/${fatJar.name}"
  new Dockerfile {
    from("hseeberger/cassandra:2.2")
    add(fatJar, fatJarTargetPath)
    env(
      "CASSANDRA_SEED_PROVIDER" -> "de.heikoseeberger.constructr.cassandra.ConstructrSeedProvider",
      "CLASSPATH" -> fatJarTargetPath
    )
  }
}
