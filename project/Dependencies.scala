import sbt._

object Version {
  final val Akka             = "2.4.12"
  final val AkkaHttp         = "10.0.0-RC2"
  final val AkkaLog4j        = "1.1.6"
  final val Circe            = "0.6.0"
  final val Log4j            = "2.7"
  final val Mockito          = "2.2.15"
  final val Scala            = "2.12.0"
  final val ScalaTest        = "3.0.1"
}

object Library {
  val akkaActor            = "com.typesafe.akka"        %% "akka-actor"              % Version.Akka
  val akkaCluster          = "com.typesafe.akka"        %% "akka-cluster"            % Version.Akka
  val akkaHttp             = "com.typesafe.akka"        %% "akka-http"               % Version.AkkaHttp
  val akkaLog4j            = "de.heikoseeberger"        %% "akka-log4j"              % Version.AkkaLog4j
  val akkaMultiNodeTestkit = "com.typesafe.akka"        %% "akka-multi-node-testkit" % Version.Akka
  val akkaSlf4j            = "com.typesafe.akka"        %% "akka-slf4j"              % Version.Akka
  val akkaTestkit          = "com.typesafe.akka"        %% "akka-testkit"            % Version.Akka
  val circeParser          = "io.circe"                 %% "circe-parser"            % Version.Circe
  val log4jCore            = "org.apache.logging.log4j" %  "log4j-core"              % Version.Log4j
  val mockitoCore          = "org.mockito"              %  "mockito-core"            % Version.Mockito
  val scalaTest            = "org.scalatest"            %% "scalatest"               % Version.ScalaTest
}
