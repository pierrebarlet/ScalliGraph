package org.thp.scalligraph.models

import java.util.{UUID, Collection ⇒ JCollection, List ⇒ JList, Map ⇒ JMap, Set ⇒ JSet}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.reflect.runtime.{universe ⇒ ru}
import scala.util.{Failure, Success, Try}

import play.api.Logger

import gremlin.scala._
import gremlin.scala.dsl._
import org.apache.tinkerpop.gremlin.process.traversal.Scope
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.query.PublicProperty
import org.thp.scalligraph.services.RichElement
import org.thp.scalligraph.{AuthorizationError, InternalError, NotFoundError}
import shapeless._

case class ResultWithTotalSize[R](result: Seq[R], totalSize: Long)

final class ScalarSteps[T: ClassTag](raw: GremlinScala[T])
    extends Steps[T, T, HNil](raw)(SingleMapping[T, T](null.asInstanceOf[T]))
    with ScalliSteps[T, T, ScalarSteps[T]] {

  lazy val logger = Logger(getClass)

  override def newInstance(raw: GremlinScala[T]): ScalarSteps[T] =
    new ScalarSteps[T](raw)

  override def map[A: ClassTag](f: T ⇒ A): ScalarSteps[A] = new ScalarSteps(raw.map(f))

  override def toList(): List[T] = {
    logger.debug(s"Execution of $raw")
    super.toList()
  }

  override def range(from: Long, to: Long): ScalarSteps[T] = new ScalarSteps(raw.range(from, to))

  override def page(from: Long, to: Long): ResultWithTotalSize[T] = {
    val byValues =
      if (to == Long.MaxValue) By(__[JSet[T]])
      else By(__[JSet[T]].range(Scope.local, from, to))
    val label = StepLabel[JSet[T]]()
    val query = raw
      .aggregate(label)
      .cap(label)
      .project(_.apply(byValues)
        .and(By(__.count(Scope.local))))
    logger.debug(s"Execution of $query")
    val (l, s) = query.head()
    ResultWithTotalSize(l.asScala.toSeq, s)
  }

  override def head(): T = {
    logger.debug(s"Execution of $raw")
    super.head()
  }

  override def headOption(): Option[T] = {
    logger.debug(s"Execution of $raw")
    super.headOption()
  }

  override def getOrFail(): Try[T] =
    headOption()
      .fold[Try[T]] {
        val typeName = implicitly[ClassTag[T]].runtimeClass.getSimpleName
        Failure(NotFoundError(s"$typeName not found"))
      }(Success.apply)
}

object ScalarSteps {
  def apply[T: ClassTag](raw: GremlinScala[T]): ScalarSteps[T] =
    new ScalarSteps[T](raw)
}

trait ScalliSteps[EndDomain, EndGraph, ThisStep <: AnyRef] { _: ThisStep ⇒
  val logger: Logger
  override def clone(): ThisStep = newInstance(raw.clone())
  def newInstance(raw: GremlinScala[EndGraph]): ThisStep
  val raw: GremlinScala[EndGraph]
  def toList(): Seq[EndDomain]
  def range(from: Long, to: Long): ThisStep
  def page(from: Long, to: Long): ResultWithTotalSize[EndDomain]
  def head(): EndDomain
  def headOption(): Option[EndDomain]
  def exists(): Boolean = {
    logger.debug(s"Execution of $raw")
    raw.limit(1).traversal.hasNext
  }
  def existsOrFail(): Try[Unit] = if (exists()) Success(()) else Failure(AuthorizationError("Unauthorized action"))

  def getOrFail(): Try[EndDomain]
  def orFail(ex: Exception): Try[EndDomain] = headOption().fold[Try[EndDomain]](Failure(ex))(Success.apply)
  def count(): Long
  def sort(orderBys: OrderBy[_]*): ThisStep = newInstance(raw.order(orderBys: _*))
  @deprecated("Use match", "0.1")
  def where(f: GremlinScala[EndGraph] ⇒ GremlinScala[_]): ThisStep                      = newInstance(raw.where(f))
  def has[A](key: Key[A], predicate: P[A])(implicit ev: EndGraph <:< Element): ThisStep = newInstance(raw.has(key, predicate))
  def map[NewEndDomain: ClassTag](f: EndDomain ⇒ NewEndDomain): ScalarSteps[NewEndDomain]
  def groupBy[K, V](k: By[K], v: By[V]): ScalarSteps[JMap[K, JCollection[V]]] =
    new ScalarSteps(raw.group(k, v))
  def groupBy[K](k: GremlinScala[K]): ScalarSteps[JMap[K, JCollection[EndGraph]]] =
    new ScalarSteps(raw.group(By(k)))
  def fold: ScalarSteps[JList[EndGraph]]                                                           = new ScalarSteps(raw.fold)
  def unfold[A: ClassTag]                                                                          = new ScalarSteps(raw.unfold[A]())
  def project[T <: Product: ClassTag](builder: ProjectionBuilder[Nil.type] ⇒ ProjectionBuilder[T]) = new ScalarSteps(raw.project(builder))
  def project[A](bys: By[_ <: A]*): ScalarSteps[JCollection[A]] = {
    val labels    = bys.map(_ ⇒ UUID.randomUUID().toString)
    val traversal = bys.foldLeft(raw.project[A](labels.head, labels.tail: _*))((t, b) ⇒ GremlinScala(b.apply(t.traversal)))
    ScalarSteps(traversal.selectValues)
  }
}

abstract class ElementSteps[E <: Product: ru.TypeTag, EndGraph <: Element, ThisStep <: ElementSteps[E, EndGraph, ThisStep]](
    raw: GremlinScala[EndGraph])(implicit val db: Database, graph: Graph)
    extends Steps[E with Entity, EndGraph, HNil](raw)(db.getModel[E].converter(db, graph).asInstanceOf[Converter.Aux[E with Entity, EndGraph]])
    with ScalliSteps[E with Entity, EndGraph, ThisStep] { _: ThisStep ⇒

  lazy val logger = Logger(getClass)

  override def headOption(): Option[E with Entity] = {
    logger.debug(s"Execution of $raw")
    super.headOption()
  }

  def get[A](authContext: AuthContext, property: PublicProperty[EndGraph, _, A]): ScalarSteps[A] = {
    val fn = property.get(raw, authContext).asInstanceOf[Seq[GremlinScala[EndGraph] ⇒ GremlinScala[A]]]
    if (fn.size == 1)
      ScalarSteps(fn.head.apply(raw))(ClassTag(property.mapping.graphTypeClass))
    else
      ScalarSteps(raw.coalesce(fn: _*))(ClassTag(property.mapping.graphTypeClass))
  }

  def get(id: String): ThisStep = newInstance(raw.has(Key("_id") of id))

  override def getOrFail(): Try[E with Entity] =
    headOption()
      .fold[Try[E with Entity]](Failure(NotFoundError(s"${ru.typeOf[E]} not found")))(Success.apply)

  override def map[T: ClassTag](f: E with Entity ⇒ T): ScalarSteps[T] = {
    implicit val const: Constructor.Aux[T, HNil, T, ScalarSteps[T]] =
      new Constructor[T, HNil] {
        type GraphType = T
        type StepsType = ScalarSteps[T]
        def apply(raw: GremlinScala[GraphType]): StepsType = new ScalarSteps[T](raw)
      }
    implicit val identityConverter: Converter.Aux[T, T] = Converter.identityConverter[T]
    map[T, T, ScalarSteps[T]](f)
  }

//  def filter(f: EntityFilter): ThisStep = newInstance(f(raw))

  override def range(from: Long, to: Long): ThisStep = newInstance(raw.range(from, to))

  override def page(from: Long, to: Long): ResultWithTotalSize[E with Entity] = {
    val size   = raw.clone.count().head()
    val result = range(from, to).toList()
    ResultWithTotalSize(result, size)
//    val byValues = if (to == Long.MaxValue) By(__[JSet[EndGraph]])
//    else By(__[JSet[EndGraph]].range(Scope.local, from, to))
//    val label = StepLabel[JSet[EndGraph]]()
//    val query = raw
//      .aggregate(label)
//      .cap(label)
//      .project(
//        _.apply(byValues)
//          .and(By(__.count(Scope.local))))
//    logger.debug(s"Execution of $query")
//    val (l, s) = query.head()
//    ResultWithTotalSize(l.asScala.toSeq.map(converter.toDomain), s)
  }

  def order(orderBys: List[OrderBy[_]]): ThisStep = newInstance(raw.order(orderBys: _*))

  class ValueMap(m: JMap[String, _]) {
    def get[A](name: String): A = m.get(name).asInstanceOf[A]
  }

  object ValueMap {
    def unapply(m: JMap[String, _]): Option[ValueMap] = Some(new ValueMap(m))
  }

  def onlyOneOfEntity[A <: Product: ru.TypeTag](elements: JList[_ <: Element]): A with Entity = {
    val size = elements.size
    if (size == 1) elements.get(0).as[A]
    else if (size > 1) throw InternalError(s"Too many ${ru.typeOf[A]} in result ($size found)")
    else throw InternalError(s"No ${ru.typeOf[A]} found")
  }

  def onlyOneOf[A](elements: JList[A]): A = {
    val size = elements.size
    if (size == 1) elements.get(0)
    else if (size > 1) throw InternalError(s"Too many elements in result ($size found)")
    else throw InternalError(s"No element found")
  }

  def atMostOneOf[A](elements: JList[A]): Option[A] = {
    val size = elements.size
    if (size == 1) Some(elements.get(0))
    else if (size > 1) throw InternalError(s"Too many elements in result ($size found)")
    else None
  }

  override def toList(): List[E with Entity] = {
    logger.debug(s"Execution of $raw")
    super.toList
  }
}

abstract class BaseVertexSteps[E <: Product: ru.TypeTag, ThisStep <: BaseVertexSteps[E, ThisStep]](raw: GremlinScala[Vertex])(
    implicit db: Database,
    val graph: Graph)
    extends ElementSteps[E, Vertex, ThisStep](raw) { _: ThisStep ⇒
}

final class VertexSteps[E <: Product: ru.TypeTag](raw: GremlinScala[Vertex])(implicit db: Database, graph: Graph)
    extends BaseVertexSteps[E, VertexSteps[E]](raw) {
  override def newInstance(raw: GremlinScala[Vertex]): VertexSteps[E] = new VertexSteps[E](raw)
}

abstract class BaseEdgeSteps[E <: Product: ru.TypeTag, FROM <: Product, TO <: Product, ThisStep <: BaseEdgeSteps[E, FROM, TO, ThisStep]](
    raw: GremlinScala[Edge])(implicit db: Database, val graph: Graph)
    extends ElementSteps[E, Edge, ThisStep](raw) { _: ThisStep ⇒
}

final class EdgeSteps[E <: Product: ru.TypeTag, FROM <: Product, TO <: Product](raw: GremlinScala[Edge])(implicit db: Database, graph: Graph)
    extends BaseEdgeSteps[E, FROM, TO, EdgeSteps[E, FROM, TO]](raw) {
  override def newInstance(raw: GremlinScala[Edge]): EdgeSteps[E, FROM, TO] = new EdgeSteps[E, FROM, TO](raw)
}
