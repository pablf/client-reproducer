import zio._
import zio.metrics._
import zio.test.Assertion._
import zio.test._

import zio.schema._

import zio.http._
import zio.http.codec._
import zio.http.endpoint._
import zio.http.netty.NettyConfig

/*
  Exposed limits through:
    Server.Config.maxInitialLineLength (URI)
    Server.Config.maxHeaderSize (Headers)
    Server.Config.disableRequestStreaming (Body size),
  Tests
    - limits on url length, header length and body size are configurable
    - default limits are safe (they are Netty's default)
 */
object SizeLimitsSpec extends ZIOSpecDefault {

  val routes = Routes(
    Method.GET / PathCodec.trailing  -> Handler.ok,
    Method.POST / PathCodec.trailing -> Handler.ok,
  )

  val CUSTOM_URL_SIZE     = 1000
  val CUSTOM_HEADER_SIZE  = 1000
  val CUSTOM_CONTENT_SIZE = 1000

  val DEFAULT_URL_SIZE     = 4096
  val DEFAULT_HEADER_SIZE  = 8192
  val DEFAULT_CONTENT_SIZE = 1024 * 100

  /*
    Checks that for `A` with size until `maxSize`, server responds with `Status.Ok` and `badStatus` after it.
   */
  def testLimit0[A](
    maxSize: Int,
    lstTestSize: Int,
    fstContent: A,
    inc: Int => A => A,
    mkRequest0: Int => A => Request,
    badStatus: Status,
  ) = {
    def loop(
      size: Int,
      lstTestSize: Int,
      content: A,
      f: A => Request,
      expected: Status,
    ): ZIO[Client, Throwable, ((Int, Status), Option[A])] = if (size >= lstTestSize)
      ZIO.succeed(((lstTestSize, expected), Some(content)))
    else
      for {
        client <- ZIO.service[Client]
        request = f(content)
        //status <- ZIO.scoped { client(request).map(_.status)}
        status <- ZIO.scoped { client(request).flatMap(v => v.ignoreBody.as(v.status))}.as(expected).catchAll{case _ => ZIO.succeed(Status.Custom(-1))}
        info   <-
          if (expected == status) loop(size + 1, lstTestSize, inc(size)(content), f, expected)
          else if (size >= lstTestSize - 2) // adding margin for differences in scala 2 and scala 3
            ZIO.succeed(((size, expected), Some(content)))
          else loop(size + 1, lstTestSize, inc(size)(content), f, expected) *> ZIO.succeed(((-1, status), None))
      } yield info

    def mkSomeRequest(port: Int): ZIO[Client, Throwable, Boolean] = for {
      client <- ZIO.service[Client]
      _ <- ZIO.scoped{client(Request.get(s"http://localhost:$port/hey"))}
    } yield true

    for {
      port <- Server.install(routes)
      mkRequest = mkRequest0(port)
      bo1 <- mkSomeRequest(port)
      out1 <- loop(0, maxSize, fstContent, mkRequest, Status.Ok)
      (info1, c) = out1
      out2 <- c match {
        case Some(content) => loop(info1._1, lstTestSize, content, mkRequest, badStatus)
        case None          => ZIO.succeed(((0, Status.Ok), None))
      }
      (info2, _) = out2
      (lstWorkingSize1, lstStatus1) = info1
      (lstWorkingSize2, lstStatus2) = info2
      bo2 <- mkSomeRequest(port)
    } yield assertTrue(
      bo1 == true,
      bo2 == true,
      maxSize - lstWorkingSize1 <= 2,
      maxSize - lstWorkingSize1 >= 0,
      lstStatus1 == Status.Ok,
      lstWorkingSize2 == lstTestSize,
      lstStatus2 == badStatus,
    )
  }

  def testLimit(size: Int, maxSize: Int, lstTestSize: Int, mkRequest0: Int => String => Request, badStatus: Status) =
    testLimit0[String](maxSize, lstTestSize, "A" * size, n => (_ ++ "A"), mkRequest0, badStatus)
  val spec: Spec[TestEnvironment with Scope, Any] = suite("SizeLimitsSpec")(
    suite("limits are configurable")(
      test("infinite segment url") {
        val urlSize = CUSTOM_URL_SIZE - 113
        testLimit(
          urlSize,
          100,
          200,
          port => path => Request.get(s"http://localhost:$port/$path"),
          Status.InternalServerError,
        )
      },
      test("infinite number of small segments url") {
        val fstUrl = List.fill(400)("A").mkString("/")
        testLimit0[String](
          94,
          200,
          fstUrl,
          _ => (_ ++ "/A"),
          port => path => Request.get(s"http://localhost:$port/$path"),
          Status.InternalServerError,
        )
      },
      test("infinite header") {
        val headerSize = CUSTOM_HEADER_SIZE - 186
        testLimit(
          headerSize,
          100,
          200,
          port => header => Request.get(s"http://localhost:$port").addHeader(Header.Custom("n", header)),
          Status.InternalServerError,
        )
      },
      test("infinite small headers") {
        val n = 30
        testLimit0[List[Header.Custom]](
          118,
          200,
          (0 until n).toList.map(s => Header.Custom(s"Header$s", "A")),
          size => (_.+:(Header.Custom(size.toString, "A"))),
          port => headers => Request.get(s"http://localhost:$port").addHeaders(Headers(headers: _*)),
          Status.InternalServerError,
        )
      },
      test("infinite body") {
        testLimit(
          CUSTOM_CONTENT_SIZE - 100,
          101,
          200,
          port => body => Request.post(s"http://localhost:$port", Body.fromString(body)),
          Status.RequestEntityTooLarge,
        )
      },
      test("infinite multi-part form") {
        testLimit0[Form](
          13,
          20,
          Form(Chunk.empty),
          size => (_ + FormField.Simple(size.toString, "A")),
          port => form => Request.post(s"http://localhost:$port", Body.fromMultipartForm(form, Boundary("-"))),
          Status.RequestEntityTooLarge,
        )
      },
    ).provide(
      Server.customized,
      ZLayer.succeed(
        Server.Config.default
          .maxHeaderSize(CUSTOM_HEADER_SIZE)
          .maxInitialLineLength(CUSTOM_URL_SIZE)
          .disableRequestStreaming(CUSTOM_CONTENT_SIZE),
      ),
      ZLayer.succeed(NettyConfig.defaultWithFastShutdown),
      Client.live,
      ZLayer.succeed(ZClient.Config.default.maxHeaderSize(15000).maxInitialLineLength(15000).disabledConnectionPool),
      DnsResolver.default,
    ),
    suite("testing default limits")(
      test("infinite multi-part form") {
        val initValue = 1300
        testLimit0[Form](
          13,
          20,
          Form(Chunk.fill(initValue)(FormField.Simple("n", "A"))),
          size => (_ + FormField.Simple(size.toString, "A")),
          port => form => Request.post(s"http://localhost:$port", Body.fromMultipartForm(form, Boundary("-"))),
          Status.RequestEntityTooLarge,
        )
      },
    ).provide(
      ZLayer.succeed(Server.Config.default),
      Server.customized,
      ZLayer.succeed(NettyConfig.defaultWithFastShutdown),
      Client.live,
      ZLayer.succeed(ZClient.Config.default.maxHeaderSize(15000).maxInitialLineLength(15000).disabledConnectionPool),
      DnsResolver.default,
    ),
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

}