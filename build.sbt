lazy val construtrRoot = project
  .copy(id = "constructr-root")
  .in(file("."))
  .enablePlugins(GitVersioning)
  .aggregate(constructrCoordination, constructrCoordinationEtcd, constructrMachine, constructrAkka, constructrCassandra)

lazy val constructrCoordination = project
  .copy(id = "constructr-coordination")
  .in(file("constructr-coordination"))
  .enablePlugins(AutomateHeaderPlugin)

lazy val constructrCoordinationEtcd = project
  .copy(id = "constructr-coordination-etcd")
  .in(file("constructr-coordination-etcd"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(constructrCoordination)

lazy val constructrMachine = project
  .copy(id = "constructr-machine")
  .in(file("constructr-machine"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(constructrCoordination)

lazy val constructrAkka = project
  .copy(id = "constructr-akka")
  .in(file("constructr-akka"))
  .enablePlugins(AutomateHeaderPlugin)
  .configs(MultiJvm)
  .dependsOn(constructrMachine, constructrCoordinationEtcd % "test->compile")

lazy val constructrCassandra = project
  .copy(id = "constructr-cassandra")
  .in(file("constructr-cassandra"))
  .enablePlugins(AutomateHeaderPlugin, DockerPlugin)
  .dependsOn(constructrMachine)

name := "constructr-root"

unmanagedSourceDirectories in Compile := Vector.empty
unmanagedSourceDirectories in Test    := Vector.empty

publishArtifact := false
