resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.bintrayRepo("slamdata-inc", "maven-public")

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "2.0.0-RC3-3")
addSbtPlugin("com.slamdata" % "sbt-slamdata" % "3.2.1")
addSbtPlugin("com.slamdata" % "sbt-quasar-datasource" % "0.1.7")
