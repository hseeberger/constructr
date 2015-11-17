lazy val constructr = project
  .in(file("."))
  .enablePlugins(GitVersioning)
  .aggregate(constructrCoordination, constructrMachine, constructrAkka, constructrCassandra)

lazy val constructrCoordination = project
  .in(file("constructr-coordination"))
  .enablePlugins(AutomateHeaderPlugin)

lazy val constructrMachine = project
  .in(file("constructr-machine"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(constructrCoordination)

lazy val constructrAkka = project
  .in(file("constructr-akka"))
  .enablePlugins(AutomateHeaderPlugin)
  .configs(MultiJvm)
  .dependsOn(constructrCoordination, constructrMachine)

lazy val constructrCassandra = project
  .in(file("constructr-cassandra"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(constructrCoordination, constructrMachine)

name := "constructr"

unmanagedSourceDirectories in Compile := Nil
unmanagedSourceDirectories in Test := Nil

publishArtifact := false
