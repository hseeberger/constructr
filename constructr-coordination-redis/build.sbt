name := "constructr-coordination-redis"

libraryDependencies ++= Vector(
  Library.rediscala,
  Library.raptureJsonCirce,
  Library.akkaTestkit % "test",
  Library.scalaTest   % "test"
)
