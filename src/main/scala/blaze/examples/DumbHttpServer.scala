package blaze.examples

import blaze.channel._
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import blaze.pipeline.stages.SerializingStage
import java.nio.ByteBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
class DumbHttpServer(port: Int) {

  private val f: PipeFactory = _.cap(new DumbHttpStage)

  val group = AsynchronousChannelGroup.withFixedThreadPool(50, java.util.concurrent.Executors.defaultThreadFactory())

  private val factory = new ServerChannelFactory(f)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object DumbHttpServer {
  def main(args: Array[String]): Unit = new DumbHttpServer(8080).run()
}
