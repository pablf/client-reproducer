val Scala212         = "2.12.19"
val Scala213         = "2.13.14"
val Scala3           = "3.3.3"
crossScalaVersions := Seq(Scala212, Scala213, Scala3)
testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
libraryDependencies ++= Seq(
        "dev.zio" %% "zio-http" % "3.0.0-RC9",
        "dev.zio" %% "zio-test" % "2.1.7" % "test",
        "dev.zio" %% "zio-test-sbt" % "2.1.7" % "test",
        "dev.zio" %% "zio" % "2.1.7",
    )

