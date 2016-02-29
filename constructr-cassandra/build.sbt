name := "constructr-cassandra"

libraryDependencies ++= Vector(
  Library.akkaActor,
  Library.akkaSlf4j, // because cassandraAll depends on Logback and hence on SLF4J
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
imageNames.in(docker) := Vector(ImageName(s"constructr/cassandra-${Version.Cassandra}:${version.in(docker).value}"))
dockerfile.in(docker) := {
  val fatJar = assemblyOutputPath.in(assembly).value
  val fatJarTargetPath = s"/usr/share/cassandra/lib/${fatJar.name}"
  new Dockerfile {
    from(s"cassandra:${Version.Cassandra}")
    copy(fatJar, fatJarTargetPath)
    run("/bin/bash",
        "-c",
        s"""|chmod go+r $fatJarTargetPath && \\
            |head -n 35 docker-entrypoint.sh > d && \\
            |echo $$'\\t'sed -ri \\'s/\\(- class_name:\\) org.apache.cassandra.locator.SimpleSeedProvider/\\\\1 de.heikoseeberger.constructr.cassandra.ConstructrSeedProvider/\\' \\"\\$$CASSANDRA_CONFIG/cassandra.yaml\\" >> d && \\
            |tail -n +36 docker-entrypoint.sh >> d && \\
            |chmod --reference docker-entrypoint.sh d && \\
            |mv d docker-entrypoint.sh""".stripMargin
      )
  }
}
