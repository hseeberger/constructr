lazy val constructr = project
  .in(file("."))
  .enablePlugins(GitVersioning)
  .aggregate(constructrCoordination, constructrAkka)

lazy val constructrCoordination = project
  .in(file("constructr-coordination"))
  .enablePlugins(AutomateHeaderPlugin)

lazy val constructrAkka = project
  .in(file("constructr-akka"))
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(constructrCoordination)
  .configs(MultiJvm)

name := "constructr"

unmanagedSourceDirectories in Compile := Nil
unmanagedSourceDirectories in Test := Nil

publishArtifact := false
