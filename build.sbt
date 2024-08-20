scalaVersion := "3.3.1"

testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
libraryDependencies ++= Seq(
        "dev.zio" %% "zio-http" % "3.0.0-RC9",
        "dev.zio" %% "zio-test" % "2.1.7" % "test",
        "dev.zio" %% "zio-test-sbt" % "2.1.7" % "test",
        "dev.zio" %% "zio" % "2.1.7",
    )

