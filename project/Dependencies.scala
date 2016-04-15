import sbt._

object Version {
  final val Akka             = "2.4.4"
  final val AkkaLog4j        = "1.1.3"
  final val Cassandra        = "3.3"
  final val Log4j            = "2.5"
  final val RaptureJsonSpray = "2.0.0-M5"
  final val Scala            = "2.11.8"
  final val ScalaMock        = "3.2.2"
  final val ScalaTest        = "2.2.6"
}

object Library {
  val akkaActor            = "com.typesafe.akka"        %% "akka-actor"                  % Version.Akka
  val akkaCluster          = "com.typesafe.akka"        %% "akka-cluster"                % Version.Akka
  val akkaHttp             = "com.typesafe.akka"        %% "akka-http-experimental"      % Version.Akka
  val akkaLog4j            = "de.heikoseeberger"        %% "akka-log4j"                  % Version.AkkaLog4j
  val akkaMultiNodeTestkit = "com.typesafe.akka"        %% "akka-multi-node-testkit"     % Version.Akka
  val akkaSlf4j            = "com.typesafe.akka"        %% "akka-slf4j"                  % Version.Akka
  val akkaTestkit          = "com.typesafe.akka"        %% "akka-testkit"                % Version.Akka
  val cassandraAll         = "org.apache.cassandra"     %  "cassandra-all"               % Version.Cassandra exclude("commons-logging", "commons-logging")
  val log4jCore            = "org.apache.logging.log4j" %  "log4j-core"                  % Version.Log4j
  val raptureJsonCirce     = "com.propensive"           %% "rapture-json-circe"          % Version.RaptureJsonSpray
  val scalaMock            = "org.scalamock"            %% "scalamock-scalatest-support" % Version.ScalaMock
  val scalaTest            = "org.scalatest"            %% "scalatest"                   % Version.ScalaTest
}
