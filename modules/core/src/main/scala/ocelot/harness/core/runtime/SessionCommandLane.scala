package ocelot.harness.core.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{
  ArrayBlockingQueue,
  Callable,
  RejectedExecutionException,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit
}

import scala.util.control.NonFatal

import ocelot.harness.core.HarnessError
import ocelot.harness.core.HarnessError.{SessionClosed, SessionOperationFailed}

private[runtime] final class SessionCommandLane(sessionName: String) extends AutoCloseable {
  private val closed = new AtomicBoolean(false)
  private val submissionLock = new AnyRef
  private val threadFactory = new ThreadFactory {
    override def newThread(runnable: Runnable): Thread = {
      val thread = new Thread(runnable, s"ocelot-harness-session-$sessionName")
      thread.setDaemon(false)
      thread
    }
  }
  private val executor = new ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue[Runnable](1),
    threadFactory,
    new ThreadPoolExecutor.AbortPolicy
  )

  def execute[A](operation: => A): Either[HarnessError, A] = submissionLock.synchronized {
    if (closed.get()) Left(SessionClosed)
    else {
      try {
        Right(
          executor
            .submit(new Callable[A] {
              override def call(): A = operation
            })
            .get()
        )
      } catch {
        case _: RejectedExecutionException => Left(SessionClosed)
        case error: java.util.concurrent.ExecutionException =>
          val cause = Option(error.getCause).getOrElse(error)
          Left(SessionOperationFailed(errorMessage(cause)))
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          Left(SessionOperationFailed("the calling thread was interrupted"))
        case NonFatal(error) => Left(SessionOperationFailed(errorMessage(error)))
      }
    }
  }

  override def close(): Unit = submissionLock.synchronized {
    if (closed.compareAndSet(false, true)) {
      executor.shutdown()
      if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
        executor.shutdownNow()
        executor.awaitTermination(5L, TimeUnit.SECONDS)
      }
    }
  }

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}
