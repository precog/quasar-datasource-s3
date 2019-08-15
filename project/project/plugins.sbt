resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.bintrayRepo("slamdata-inc", "maven-public")
resolvers += Resolver.bintrayIvyRepo("djspiewak", "ivy")

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "2.0.0-RC3-3")
addSbtPlugin("com.slamdata"    % "sbt-slamdata" % "3.1.0")
