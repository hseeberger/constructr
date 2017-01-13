addSbtPlugin("com.dwijnand"      % "sbt-travisci"  % "1.0.0")
addSbtPlugin("com.geirsson"      % "sbt-scalafmt"  % "0.4.10")
addSbtPlugin("com.thesamet"      % "sbt-protoc"    % "0.99.3")
addSbtPlugin("com.jsuereth"      % "sbt-pgp"       % "1.0.0")
addSbtPlugin("com.typesafe.sbt"  % "sbt-git"       % "0.8.5")
addSbtPlugin("com.typesafe.sbt"  % "sbt-multi-jvm" % "0.3.11")
addSbtPlugin("de.heikoseeberger" % "sbt-header"    % "1.6.0")
addSbtPlugin("me.lessis"         % "bintray-sbt"   % "0.3.0")

libraryDependencies += "com.trueaccord.scalapb" %% "compilerplugin" % "0.5.46"
