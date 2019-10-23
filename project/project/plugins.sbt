resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.bintrayRepo("slamdata-inc", "maven-public")
resolvers += Resolver.bintrayIvyRepo("djspiewak", "ivy")

addSbtPlugin("com.slamdata"    % "sbt-slamdata" % "5.1.1")
