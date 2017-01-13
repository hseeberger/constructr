// *****************************************************************************
// Projects
// *****************************************************************************

lazy val constructr =
  project
    .in(file("."))
    .enablePlugins(GitVersioning)
    .disablePlugins(ProtocPlugin)
    .aggregate(
      core,
      coordination,
      `coordination-etcd-2`,
      `coordination-etcd-3`
    )
    .settings(settings)
    .settings(
      unmanagedSourceDirectories.in(Compile) := Seq.empty,
      unmanagedSourceDirectories.in(Test) := Seq.empty,
      publishArtifact := false
    )

lazy val core =
  project
    .enablePlugins(AutomateHeaderPlugin)
    .disablePlugins(ProtocPlugin)
    .configs(MultiJvm)
    .dependsOn(coordination,`coordination-etcd-2` % "test->compile")
    .settings(settings)
    .settings(multiJvmSettings)
    .settings(
      name := "constructr",
      libraryDependencies ++= Seq(
        library.akkaCluster,
        library.akkaLog4j            % Test,
        library.akkaMultiNodeTestkit % Test,
        library.akkaTestkit          % Test,
        library.log4jCore            % Test,
        library.mockitoCore          % Test,
        library.scalaTest            % Test
      )
    )

lazy val coordination =
  project
    .enablePlugins(AutomateHeaderPlugin)
    .disablePlugins(ProtocPlugin)
    .settings(settings)
    .settings(
      name := "constructr-coordination",
      libraryDependencies ++= Seq(
        library.akkaActor
      )
    )

lazy val `coordination-etcd-2` =
  project
    .enablePlugins(AutomateHeaderPlugin)
    .disablePlugins(ProtocPlugin)
    .dependsOn(coordination)
    .settings(settings)
    .settings(
      name := "constructr-coordination-etcd-2",
      libraryDependencies ++= Seq(
        library.akkaHttp,
        library.circeParser,
        library.akkaTestkit % Test,
        library.scalaTest   % Test
      )
    )

lazy val `coordination-etcd-3` =
  project
    .enablePlugins(AutomateHeaderPlugin)
    .dependsOn(coordination)
    .settings(settings)
    .settings(pbSettings)
    .settings(
      name := "constructr-coordination-etcd-3",
      libraryDependencies ++= Seq(
        library.scalaTest % Test
      )
    )

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {
    object Version {
      val akka      = "2.4.16"
      val akkaHttp  = "10.0.0"
      val akkaLog4j = "1.2.2"
      val circe     = "0.6.1"
      val grpc      = "1.0.3"
      val log4j     = "2.7"
      val mockito   = "2.3.7"
      val scalapb   = com.trueaccord.scalapb.compiler.Version.scalapbVersion
      val scalaTest = "3.0.1"
    }
    val akkaActor            = "com.typesafe.akka"        %% "akka-actor"              % Version.akka
    val akkaCluster          = "com.typesafe.akka"        %% "akka-cluster"            % Version.akka
    val akkaHttp             = "com.typesafe.akka"        %% "akka-http"               % Version.akkaHttp
    val akkaLog4j            = "de.heikoseeberger"        %% "akka-log4j"              % Version.akkaLog4j
    val akkaMultiNodeTestkit = "com.typesafe.akka"        %% "akka-multi-node-testkit" % Version.akka
    val akkaSlf4j            = "com.typesafe.akka"        %% "akka-slf4j"              % Version.akka
    val akkaTestkit          = "com.typesafe.akka"        %% "akka-testkit"            % Version.akka
    val circeParser          = "io.circe"                 %% "circe-parser"            % Version.circe
    val grpcNetty            = "io.grpc"                  %  "grpc-netty"              % Version.grpc
    val grpcStub             = "io.grpc"                  %  "grpc-stub"               % Version.grpc
    val log4jCore            = "org.apache.logging.log4j" %  "log4j-core"              % Version.log4j
    val mockitoCore          = "org.mockito"              %  "mockito-core"            % Version.mockito
    val scalapbRuntime       = "com.trueaccord.scalapb"   %% "scalapb-runtime"         % Version.scalapb
    val scalapbRuntimeGrpc   = "com.trueaccord.scalapb"   %% "scalapb-runtime-grpc"    % Version.scalapb
    val scalaTest            = "org.scalatest"            %% "scalatest"               % Version.scalaTest
}

// *****************************************************************************
// Settings
// *****************************************************************************        |

lazy val settings =
  commonSettings ++
  scalafmtSettings ++
  gitSettings ++
  headerSettings ++
  sonatypeSettings

lazy val commonSettings =
  Seq(
    // scalaVersion from .travis.yml
    // crossScalaVersions from .travis.yml
    organization := "de.heikoseeberger",
    licenses += ("Apache 2.0",
                 url("http://www.apache.org/licenses/LICENSE-2.0")),
    mappings.in(Compile, packageBin) +=
      baseDirectory.in(ThisBuild).value / "LICENSE" -> "LICENSE",
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding", "UTF-8"
    ),
    javacOptions ++= Seq(
      "-source", "1.8",
      "-target", "1.8"
    ),
    unmanagedSourceDirectories.in(Compile) :=
      Seq(scalaSource.in(Compile).value),
    unmanagedSourceDirectories.in(Test) :=
      Seq(scalaSource.in(Test).value)
)

lazy val scalafmtSettings =
  reformatOnCompileSettings ++
  Seq(
    formatSbtFiles := false,
    scalafmtConfig :=
      Some(baseDirectory.in(ThisBuild).value / ".scalafmt.conf"),
    ivyScala :=
      ivyScala.value.map(_.copy(overrideScalaVersion = sbtPlugin.value)) // TODO Remove once this workaround no longer needed (https://github.com/sbt/sbt/issues/2786)!
  )

lazy val gitSettings =
  Seq(
    git.useGitDescribe := true
  )

import de.heikoseeberger.sbtheader.license.Apache2_0
lazy val headerSettings =
  Seq(
    headers := Map("scala" -> Apache2_0("2015", "Heiko Seeberger"))
  )

lazy val sonatypeSettings =
  Seq(
    homepage := Some(url("https://github.com/hseeberger/constructr")),
    scmInfo := Some(ScmInfo(url("https://github.com/hseeberger/constructr"),
                            "git@github.com:hseeberger/constructr.git")),
    developers += Developer("hseeberger",
                            "Heiko Seeberger",
                            "mail@heikoseeberger.de",
                            url("https://github.com/hseeberger")),
    pomIncludeRepository := (_ => false)
  )

import ScalaFmtPlugin.configScalafmtSettings
lazy val multiJvmSettings =
  AutomateHeaderPlugin.automateFor(Compile, Test, MultiJvm) ++
  HeaderPlugin.settingsFor(Compile, Test, MultiJvm) ++
  inConfig(MultiJvm)(configScalafmtSettings) ++
  Seq(
    unmanagedSourceDirectories.in(MultiJvm) :=
      Seq(scalaSource.in(MultiJvm).value),
    test.in(Test) := {
      val testValue = test.in(Test).value
      test.in(MultiJvm).value
      testValue
    },
    compileInputs.in(MultiJvm, compile) := {
      val scalafmtValue = scalafmt.in(MultiJvm).value
      compileInputs.in(MultiJvm, compile).value
    }
  )

lazy val pbSettings =
  Seq(
    PB.protoSources.in(Compile) :=
      Seq(sourceDirectory.in(Compile).value / "proto"),
    PB.targets.in(Compile) :=
      Seq(scalapb.gen() -> sourceManaged.in(Compile).value),
    libraryDependencies ++= Seq(
      library.scalapbRuntimeGrpc,
      library.scalapbRuntime % "protobuf"
    )
  )
