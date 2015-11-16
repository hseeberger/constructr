lazy val constructr = project
  .in(file("."))
  .enablePlugins(GitVersioning)
  .aggregate(constructrAkka)

lazy val constructrAkka = project
  .in(file("constructr-akka"))
  .enablePlugins(AutomateHeaderPlugin)
  .configs(MultiJvm)

name := "constructr"

unmanagedSourceDirectories in Compile := Nil
unmanagedSourceDirectories in Test := Nil

publishArtifact := false
