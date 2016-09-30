lazy val constructr = project
  .in(file("."))
  .enablePlugins(GitVersioning)
  .aggregate(
    `constructr-coordination`,
    `constructr-coordination-etcd`,
    `constructr-machine`,
    `constructr-akka`,
    `constructr-cassandra`
  )

lazy val `constructr-coordination` = project
  .in(file("constructr-coordination"))
  .enablePlugins(AutomateHeaderPlugin)

lazy val `constructr-coordination-etcd` = project
  .in(file("constructr-coordination-etcd"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`constructr-coordination`)

lazy val `constructr-machine` = project
  .in(file("constructr-machine"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`constructr-coordination`)

lazy val `constructr-akka` = project
  .in(file("constructr-akka"))
  .enablePlugins(AutomateHeaderPlugin)
  .configs(MultiJvm)
  .dependsOn(`constructr-machine`, `constructr-coordination-etcd` % "test->compile")

lazy val `constructr-cassandra` = project
  .in(file("constructr-cassandra"))
  .enablePlugins(AutomateHeaderPlugin, DockerPlugin)
  .dependsOn(`constructr-machine`, `constructr-coordination-etcd` % "runtime->compile")

unmanagedSourceDirectories.in(Compile) := Vector.empty
unmanagedSourceDirectories.in(Test)    := Vector.empty

publishArtifact := false
