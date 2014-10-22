package com.outr.net.http

import org.powerscala.event.Listenable
import java.text.SimpleDateFormat
import com.outr.net.http.request.HttpRequest
import org.powerscala._
import com.outr.net.http.response.HttpResponse
import org.powerscala.concurrent.{Time, Executor}
import java.util.concurrent.ScheduledFuture
import org.powerscala.concurrent.Time._
import org.powerscala.event.processor.UnitProcessor

/**
 * @author Matt Hicks <matt@outr.com>
 */
trait HttpApplication extends Listenable with HttpHandler with Updatable with Disposable {
  private val requestResponses = new LocalStack[HttpRequest]

  /**
   * Option[HttpRequest]
   */
  def requestOption = requestResponses.get()
  /**
   * The HttpRequest for the current thread. This will throw a RuntimeException if there is no request contextualized.
   */
  def request = requestOption.getOrElse(throw new RuntimeException(s"HttpRequest is not set for the current thread (${getClass.getSimpleName})!"))

  def contextualize[R](request: HttpRequest)(f: => R) = requestResponses.context(request)(f)

  @volatile private var _initialized = false
  @volatile private var running = false
  @volatile private var _updater: ScheduledFuture[_] = _

  val disposed = new UnitProcessor[HttpApplication]("disposed")

  def initialized = _initialized
  def isRunning = running

  /**
   * The frequency the application will be updated in seconds.
   *
   * Defaults to 5 seconds.
   */
  def updateFrequency: Double = 5.seconds

  final def initialize() = synchronized {
    if (!initialized) {
      init()

      var previous = System.nanoTime()
      _updater = Executor.scheduleWithFixedDelay(0.0, updateFrequency) {
        val current = System.nanoTime()
        val delta = Time.fromNanos(current - previous)
        update(delta)
        previous = current
      }
      _initialized = true
      running = true
    }
  }

  /**
   * Called once as the application is initialized.
   */
  protected def init(): Unit

  override def onReceive(request: HttpRequest, response: HttpResponse) = response

  /**
   * This method is invoked per request to allow wrapping around the entire request/response functionality.
   *
   * @param f the function to manage the request / response process.
   */
  def around[R](f: => R): R = f

  /**
   * Called once when the application is terminating (not guaranteed to be executed).
   */
  def dispose(): Unit = {
    _updater.cancel(false)

    disposed.fire(this)
    running = false
  }
}

object HttpApplication {
  def DateParser = new SimpleDateFormat("EEE, dd MMMM yyyy HH:mm:ss zzz")
}