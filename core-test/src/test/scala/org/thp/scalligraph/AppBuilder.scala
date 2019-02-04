package org.thp.scalligraph

import scala.reflect.ClassTag

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.concurrent.Akka

import akka.actor.{Actor, ActorRef, Props}
import com.google.inject.name.Names
import com.google.inject.util.Providers
import javax.inject.Provider
import net.codingwell.scalaguice.ScalaModule

class AppBuilder extends ScalaModule {

  private var initialized             = false
  private var init: Function[Unit, _] = identity[Unit]

  override def configure(): Unit = {
    init(())
    ()
  }

  def bind[T: Manifest, TImpl <: T: Manifest]: AppBuilder = {
    if (initialized) throw InternalError("Bind is not permitted after app use")
    init = init.andThen(_ ⇒ bind[T].to[TImpl])
    this
  }

  def bindInstance[T: Manifest](instance: T): AppBuilder = {
    if (initialized) throw InternalError("Bind is not permitted after app use")
    init = init.andThen(_ ⇒ bind[T].toInstance(instance))
    this
  }

  def bindEagerly[T: Manifest]: AppBuilder = {
    if (initialized) throw InternalError("Bind is not permitted after app use")
    init = init.andThen(_ ⇒ bind[T].asEagerSingleton())
    this
  }

  def bindToProvider[T: Manifest](provider: Provider[T]): AppBuilder = {
    if (initialized) throw InternalError("Bind is not permitted after app use")
    init = init.andThen(_ ⇒ bind[T].toProvider(provider))
    this
  }

  def bindActor[T <: Actor: ClassTag](name: String, props: Props ⇒ Props = identity): AppBuilder = {
    if (initialized) throw InternalError("Bind is not permitted after app use")
    init = init.andThen { _ ⇒
      bind(classOf[ActorRef])
        .annotatedWith(Names.named(name))
        .toProvider(Providers.guicify(Akka.providerOf[T](name, props)))
        .asEagerSingleton()
    }
    this
  }

  lazy val app: Application = {
    initialized = true
    GuiceApplicationBuilder(modules = Seq(this)).build()
  }

  def instanceOf[T: ClassTag]: T = app.injector.instanceOf[T]
}

object AppBuilder {
  def apply() = new AppBuilder
}