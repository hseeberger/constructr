lazy val constructr =
  project
    .in(file("."))
    .enablePlugins(GitVersioning)
    .aggregate(
      `constructr-coordination`,
      `constructr-coordination-etcd`,
      `constructr-machine`
    )

lazy val `constructr-coordination` =
  project
    .enablePlugins(AutomateHeaderPlugin)

lazy val `constructr-coordination-etcd` =
  project
    .enablePlugins(AutomateHeaderPlugin)
    .dependsOn(`constructr-coordination`)

lazy val `constructr-machine` =
  project
    .enablePlugins(AutomateHeaderPlugin)
    .configs(MultiJvm)
    .dependsOn(
      `constructr-coordination`,
      `constructr-coordination-etcd` % "test->compile"
    )

unmanagedSourceDirectories.in(Compile) := Vector.empty
unmanagedSourceDirectories.in(Test)    := Vector.empty

publishArtifact := false
