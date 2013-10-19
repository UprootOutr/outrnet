package com.outr.net.http

import org.powerscala.event.Listenable
import java.text.SimpleDateFormat
import com.outr.net.http.response.HttpResponseStatus
import com.outr.net.http.request.HttpRequest
import org.powerscala._
import com.outr.net.http.response.HttpResponse
import org.powerscala.concurrent.{Time, Executor}
import java.util.concurrent.ScheduledFuture
import org.powerscala.concurrent.Time._
import org.powerscala.event.processor.UnitProcessor
import java.util.concurrent.atomic.AtomicReference

/**
 * @author Matt Hicks <matt@outr.com>
 */
trait HttpApplication extends Listenable with HttpHandler with Updatable with Disposable {
  HttpApplication.current = this

  private val stack = new LocalStack[Storage[String, Any]]
  def requestContext = stack()

  @volatile private var _initialized = false
  @volatile private var _updater: ScheduledFuture[_] = _

  val disposed = new UnitProcessor[HttpApplication]("disposed")

  def initialized = _initialized

  /**
   * The frequency the application will be updated in seconds.
   *
   * Defaults to 5 seconds.
   */
  def updateFrequency: Double = 5.seconds

  /**
   * The HttpRequest for the current thread. This will return null if there is no request contextualized.
   */
  def request = requestContext[HttpRequest]("request")

  final def initialize() = synchronized {
    if (!initialized) {
      HttpApplication.current = this
      init()

      var previous = System.nanoTime()
      _updater = Executor.scheduleWithFixedDelay(0.0, updateFrequency) {
        val current = System.nanoTime()
        val delta = Time.fromNanos(current - previous)
        update(delta)
        previous = current
      }
      _initialized = true
    }
  }

  /**
   * Called once as the application is initialized.
   */
  protected def init(): Unit

  /**
   * Called once when the application is terminating (not guaranteed to be executed).
   */
  def dispose(): Unit = {
    _updater.cancel(false)

    disposed.fire(this)
  }

  protected def processRequest(request: HttpRequest, response: HttpResponse) = {
    onReceive(request, response)
  }

  final def receive(request: HttpRequest) = contextualize(request) {
    processRequest(request, HttpResponse(status = HttpResponseStatus.NotFound))
  }

  /**
   * Contextualizes the current thread with this HttpRequest and executes the supplied code block. Upon completion the
   * the context will be returned to its previous state.
   *
   * @param request the HttpRequest to put into the current context
   * @param f the code block to execute
   * @tparam T the return type
   * @return T
   */
  def contextualize[T](request: HttpRequest)(f: => T) = {
    HttpApplication.current = this
    stack.context(new MapStorage[String, Any]) {   // Make the stack available for this context
      requestContext("request") = request                    // Put the request into the storage
      f
    }
  }
}

object HttpApplication {
  def DateParser = new SimpleDateFormat("EEE, dd MMMM yyyy HH:mm:ss zzz")

  private val _current = new AtomicReference[HttpApplication]()

  def current_=(application: HttpApplication) = _current.set(application)
  def current = _current.get()

  def apply[T <: HttpApplication]() = current.asInstanceOf[T]
}