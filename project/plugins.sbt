resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.bintrayRepo("slamdata-inc", "maven-public")

addSbtPlugin("com.slamdata" % "sbt-slamdata" % "5.1.0")
addSbtPlugin("com.slamdata" % "sbt-quasar-datasource" % "0.1.7")
