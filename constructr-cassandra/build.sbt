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
version.in(docker)    := "latest"
imageNames.in(docker) := List(ImageName(s"constructr/cassandra-${Version.Cassandra}:${version.in(docker).value}"))
dockerfile.in(docker) := {
  val fatJar = assemblyOutputPath.in(assembly).value
  val fatJarTargetPath = s"/${fatJar.name}"
  new Dockerfile {
    from(s"hseeberger/cassandra:${Version.Cassandra}")
    add(fatJar, fatJarTargetPath)
    env(
      "CASSANDRA_SEED_PROVIDER" -> "de.heikoseeberger.constructr.cassandra.ConstructrSeedProvider",
      "CLASSPATH" -> fatJarTargetPath
    )
  }
}
