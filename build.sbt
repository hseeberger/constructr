lazy val root = project
  .copy(id = "root")
  .in(file("."))
  .enablePlugins(GitVersioning)
  .aggregate(constructrCoordination, constructrMachine, constructrAkka, constructrCassandra)

lazy val constructrCoordination = project
  .copy(id = "constructr-coordination")
  .in(file("constructr-coordination"))
  .enablePlugins(AutomateHeaderPlugin)

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
  .dependsOn(constructrCoordination, constructrMachine)

lazy val constructrCassandra = project
  .copy(id = "constructr-cassandra")
  .in(file("constructr-cassandra"))
  .enablePlugins(AutomateHeaderPlugin, DockerPlugin)
  .dependsOn(constructrCoordination, constructrMachine)

name := "constructr"

unmanagedSourceDirectories in Compile := Nil
unmanagedSourceDirectories in Test := Nil

publishArtifact := false
