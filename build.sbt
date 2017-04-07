// *****************************************************************************
// Projects
// *****************************************************************************

lazy val constructr =
  project
    .in(file("."))
    .enablePlugins(GitVersioning)
    .aggregate(core, coordination, `coordination-etcd`)
    .settings(settings)
    .settings(
      unmanagedSourceDirectories.in(Compile) := Seq.empty,
      unmanagedSourceDirectories.in(Test) := Seq.empty,
      publishArtifact := false
    )

lazy val core =
  project
    .enablePlugins(AutomateHeaderPlugin)
    .configs(MultiJvm)
    .dependsOn(coordination,`coordination-etcd` % "test->compile")
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
    .settings(settings)
    .settings(
      name := "constructr-coordination",
      libraryDependencies ++= Seq(
        library.akkaActor
      )
    )

lazy val `coordination-etcd` =
  project
    .enablePlugins(AutomateHeaderPlugin)
    .dependsOn(coordination)
    .settings(settings)
    .settings(
      name := "constructr-coordination-etcd",
      libraryDependencies ++= Seq(
        library.akkaHttp,
        library.circeParser,
        library.akkaTestkit % Test,
        library.scalaTest   % Test
      )
    )

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {
    object Version {
      final val akka      = "2.4.17"
      final val akkaHttp  = "10.0.5"
      final val akkaLog4j = "1.3.0"
      final val circe     = "0.7.1"
      final val log4j     = "2.8.2"
      final val mockito   = "2.7.22"
      final val scalaTest = "3.0.1"
    }
    val akkaActor            = "com.typesafe.akka"        %% "akka-actor"              % Version.akka
    val akkaCluster          = "com.typesafe.akka"        %% "akka-cluster"            % Version.akka
    val akkaHttp             = "com.typesafe.akka"        %% "akka-http"               % Version.akkaHttp
    val akkaLog4j            = "de.heikoseeberger"        %% "akka-log4j"              % Version.akkaLog4j
    val akkaMultiNodeTestkit = "com.typesafe.akka"        %% "akka-multi-node-testkit" % Version.akka
    val akkaSlf4j            = "com.typesafe.akka"        %% "akka-slf4j"              % Version.akka
    val akkaTestkit          = "com.typesafe.akka"        %% "akka-testkit"            % Version.akka
    val circeParser          = "io.circe"                 %% "circe-parser"            % Version.circe
    val log4jCore            = "org.apache.logging.log4j" %  "log4j-core"              % Version.log4j
    val mockitoCore          = "org.mockito"              %  "mockito-core"            % Version.mockito
    val scalaTest            = "org.scalatest"            %% "scalatest"               % Version.scalaTest
}

// *****************************************************************************
// Settings
// *****************************************************************************        |

lazy val settings =
  commonSettings ++
  gitSettings ++
  headerSettings ++
  sonatypeSettings ++
  bintraySettings

lazy val commonSettings =
  Seq(
    // scalaVersion from .travis.yml
    // crossScalaVersions from .travis.yml
    organization := "de.heikoseeberger",
    licenses += ("Apache 2.0",
                 url("http://www.apache.org/licenses/LICENSE-2.0")),
    mappings.in(Compile, packageBin) += baseDirectory.in(ThisBuild).value / "LICENSE" -> "LICENSE",
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding", "UTF-8"
    ),
    unmanagedSourceDirectories.in(Compile) := Seq(scalaSource.in(Compile).value),
    unmanagedSourceDirectories.in(Test) := Seq(scalaSource.in(Test).value)
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

lazy val bintraySettings =
  Seq(
    bintrayPackage := "constructr"
  )

lazy val multiJvmSettings =
  automateScalafmtFor(MultiJvm) ++
  AutomateHeaderPlugin.automateFor(Compile, Test, MultiJvm) ++
  HeaderPlugin.settingsFor(Compile, Test, MultiJvm) ++
  Seq(
    unmanagedSourceDirectories.in(MultiJvm) := Seq(scalaSource.in(MultiJvm).value),
    test.in(Test) := test.in(MultiJvm).dependsOn(test.in(Test)).value
  )
