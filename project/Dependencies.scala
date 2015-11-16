import sbt._

object Version {
  final val Akka             = "2.4.0"
  final val AkkaHttp         = "2.0-M1"
  final val AkkaLog4j        = "1.0.2"
  final val Log4j            = "2.4.1"
  final val RaptureJsonSpray = "1.1.0"
  final val Scala            = "2.11.7"
  final val ScalaCheck       = "1.12.5"
  final val ScalaTest        = "2.2.5"
}

object Library {
  val akkaActor            = "com.typesafe.akka"        %% "akka-actor"              % Version.Akka
  val akkaCluster          = "com.typesafe.akka"        %% "akka-cluster"            % Version.Akka
  val akkaHttp             = "com.typesafe.akka"        %% "akka-http-experimental"  % Version.AkkaHttp
  val akkaLog4j            = "de.heikoseeberger"        %% "akka-log4j"              % Version.AkkaLog4j
  val akkaMultiNodeTestkit = "com.typesafe.akka"        %% "akka-multi-node-testkit" % Version.Akka
  val akkaTestkit          = "com.typesafe.akka"        %% "akka-testkit"            % Version.Akka
  val log4jApi             = "org.apache.logging.log4j" %  "log4j-api"               % Version.Log4j
  val log4jCore            = "org.apache.logging.log4j" %  "log4j-core"              % Version.Log4j
  val raptureJsonSpray     = "com.propensive"           %% "rapture-json-spray"      % Version.RaptureJsonSpray
  val scalaCheck           = "org.scalacheck"           %% "scalacheck"              % Version.ScalaCheck
  val scalaTest            = "org.scalatest"            %% "scalatest"               % Version.ScalaTest
}
