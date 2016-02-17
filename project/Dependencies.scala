import sbt._

object Version {
  final val Akka             = "2.4.2"
  final val AkkaLog4j        = "1.1.2"
  final val Cassandra        = "3.0.2"
  final val Log4j            = "2.5"
  final val RaptureJsonSpray = "1.1.0"
  final val Scala            = "2.11.7"
  final val ScalaTest        = "2.2.6"
}

object Library {
  val akkaActor            = "com.typesafe.akka"        %% "akka-actor"              % Version.Akka
  val akkaCluster          = "com.typesafe.akka"        %% "akka-cluster"            % Version.Akka
  val akkaHttp             = "com.typesafe.akka"        %% "akka-http-experimental"  % Version.Akka
  val akkaLog4j            = "de.heikoseeberger"        %% "akka-log4j"              % Version.AkkaLog4j
  val akkaMultiNodeTestkit = "com.typesafe.akka"        %% "akka-multi-node-testkit" % Version.Akka
  val akkaSlf4j            = "com.typesafe.akka"        %% "akka-slf4j"              % Version.Akka
  val akkaTestkit          = "com.typesafe.akka"        %% "akka-testkit"            % Version.Akka
  val cassandraAll         = "org.apache.cassandra"     %  "cassandra-all"           % Version.Cassandra exclude("commons-logging", "commons-logging")
  val log4jCore            = "org.apache.logging.log4j" %  "log4j-core"              % Version.Log4j
  val raptureJsonSpray     = "com.propensive"           %% "rapture-json-spray"      % Version.RaptureJsonSpray
  val scalaTest            = "org.scalatest"            %% "scalatest"               % Version.ScalaTest
}
