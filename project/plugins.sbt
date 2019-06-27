resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.bintrayRepo("slamdata-inc", "maven-public")

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.1.0-M7")
addSbtPlugin("com.slamdata" % "sbt-slamdata" % "2.5.1")
addSbtPlugin("com.slamdata" % "sbt-quasar-datasource" % "0.0.6")
