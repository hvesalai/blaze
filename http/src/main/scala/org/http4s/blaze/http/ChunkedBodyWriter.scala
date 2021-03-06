package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.HttpServerStage.RouteResult
import org.http4s.blaze.util.Execution

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

// Write data in a chunked manner
private class ChunkedBodyWriter(forceClose: Boolean,
                                private var prelude: ByteBuffer,
                                stage: HttpServerStage,
                                maxCacheSize: Int) extends InternalWriter {

  private val cache = new ListBuffer[ByteBuffer]
  private var cacheSize = 0
  private var closed = false

  // reuse the ListBuffer as our lock but give it a better name
  private val lock = cache


  override def write(buffer: ByteBuffer): Future[Unit] = lock.synchronized {
    if (closed) InternalWriter.closedChannelException
    else if (!buffer.hasRemaining) InternalWriter.cachedSuccess
    else {
      cache += buffer
      cacheSize += buffer.remaining()

      if (cacheSize > maxCacheSize) flush()
      else InternalWriter.cachedSuccess
    }
  }

  override def flush(): Future[Unit] = lock.synchronized {
    if (closed)  InternalWriter.closedChannelException
    else {
      val buffs = {
        val cacheBuffs = if (cache.nonEmpty) {
          cache += ChunkedBodyWriter.CRLFBuffer
          val buffs = lengthBuffer::cache.result()
          cache.clear()
          cacheSize = 0
          buffs
        } else Nil

        if (prelude != null) {
          val p = prelude
          prelude = null
          p::cacheBuffs
        }
        else cacheBuffs
      }

      if (buffs.nonEmpty) stage.channelWrite(buffs)
      else InternalWriter.cachedSuccess
    }
  }

  override def close(): Future[RouteResult] = lock.synchronized {
    if (closed)  InternalWriter.closedChannelException
    else {

      val f = if (cache.nonEmpty || prelude != null) flush().flatMap(_ => writeTermination())(Execution.directec)
      else writeTermination()

      f.map( _ => lock.synchronized {
        closed = true
        if (forceClose || !stage.contentComplete()) HttpServerStage.Close
        else HttpServerStage.Reload
      })(Execution.directec)
    }
  }

  private def writeTermination(): Future[Unit] = {
    stage.channelWrite(ByteBuffer.wrap(ChunkedBodyWriter.terminationBytes))
  }

  private def lengthBuffer: ByteBuffer = {
    val bytes = Integer.toHexString(cacheSize).getBytes(StandardCharsets.US_ASCII)
    val b = ByteBuffer.allocate(bytes.length + 2)
    b.put(bytes).put(ChunkedBodyWriter.CRLFBytes).flip()
    b
  }
}

private object ChunkedBodyWriter {
  private val CRLFBytes = "\r\n".getBytes(StandardCharsets.US_ASCII)
  private val terminationBytes = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
  private def CRLFBuffer = ByteBuffer.wrap(CRLFBytes)
}
