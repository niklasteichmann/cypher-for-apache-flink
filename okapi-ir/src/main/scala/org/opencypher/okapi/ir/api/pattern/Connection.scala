/*
 * Copyright (c) 2016-2018 "Neo4j Sweden, AB" [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Attribution Notice under the terms of the Apache License 2.0
 *
 * This work was created by the collective efforts of the openCypher community.
 * Without limiting the terms of Section 6, any Derivative Work that is not
 * approved by the public consensus process of the openCypher Implementers Group
 * should not be described as “Cypher” (and Cypher® is a registered trademark of
 * Neo4j Inc.) or as "openCypher". Extensions by implementers or prototypes or
 * proposals for change that have been documented or implemented should only be
 * described as "implementation extensions to Cypher" or as "proposed changes to
 * Cypher that are not yet approved by the openCypher community".
 */
package org.opencypher.okapi.ir.api.pattern

import org.opencypher.okapi.ir.api._
import org.opencypher.okapi.ir.api.pattern.Orientation.{Cyclic, Directed, Undirected}

import scala.language.higherKinds

sealed trait Connection {
  type SELF[XO, XE] <: Connection { type O = XO; type E = XE }
  type O <: Orientation[E]
  type E <: Endpoints

  def orientation: Orientation[E]
  def endpoints: E

  def source: IRField
  def target: IRField

  def flip: SELF[O, E]

  override def hashCode(): Int = orientation.hash(endpoints, seed)
  override def equals(obj: scala.Any): Boolean = super.equals(obj) || (obj != null && equalsIfNotEq(obj))

  protected def seed: Int
  protected def equalsIfNotEq(obj: scala.Any): Boolean
}

sealed trait DirectedConnection extends Connection {
  override type SELF[XO, XE] <: DirectedConnection { type O = XO; type E = XE }
  override type O = Directed.type
  override type E = DifferentEndpoints

  final override def orientation: Orientation.Directed.type = Directed

  final override def source: IRField = endpoints.source
  final override def target: IRField = endpoints.target
}

sealed trait UndirectedConnection extends Connection {
  override type SELF[XO, XE] <: UndirectedConnection { type O = XO; type E = XE }
  override type O = Undirected.type
  override type E = DifferentEndpoints

  final override def orientation: Orientation.Undirected.type = Undirected

  final override def source: IRField = endpoints.source
  final override def target: IRField = endpoints.target
}

sealed trait CyclicConnection extends Connection {
  override type SELF[XO, XE] <: CyclicConnection { type O = XO; type E = XE }
  override type O = Cyclic.type
  override type E = IdenticalEndpoints

  final override def orientation: Orientation.Cyclic.type = Cyclic

  final override def source: IRField = endpoints.field
  final override def target: IRField = endpoints.field
}

case object SingleRelationship {
  val seed = "SimpleConnection".hashCode
}

sealed trait SingleRelationship extends Connection {
  override type SELF[XO, XE] <: SingleRelationship { type O = XO; type E = XE }
  final protected override def seed = SingleRelationship.seed
}

final case class DirectedRelationship(endpoints: DifferentEndpoints)
  extends SingleRelationship with DirectedConnection {

  override type SELF[XO, XE] = DirectedRelationship { type O = XO; type E = XE }

  protected def equalsIfNotEq(obj: scala.Any): Boolean = obj match {
    case other: DirectedRelationship => orientation.eqv(endpoints, other.endpoints)
    case _ => false
  }

  override def flip: DirectedRelationship = copy(endpoints.flip)
}

case object DirectedRelationship {
  def apply(source: IRField, target: IRField): SingleRelationship = Endpoints(source, target) match {
    case ends: IdenticalEndpoints => CyclicRelationship(ends)
    case ends: DifferentEndpoints => DirectedRelationship(ends)
  }
}

final case class UndirectedRelationship(endpoints: DifferentEndpoints)
  extends SingleRelationship with UndirectedConnection {

  override type SELF[XO, XE] = UndirectedRelationship { type O = XO; type E = XE }

  protected def equalsIfNotEq(obj: scala.Any): Boolean = obj match {
    case other: UndirectedRelationship => orientation.eqv(endpoints, other.endpoints)
    case _ => false
  }

  override def flip: UndirectedRelationship = copy(endpoints.flip)
}

case object UndirectedRelationship {
  def apply(source: IRField, target: IRField): SingleRelationship = Endpoints(source, target) match {
    case ends: IdenticalEndpoints => CyclicRelationship(ends)
    case ends: DifferentEndpoints => UndirectedRelationship(ends)
  }
}

final case class CyclicRelationship(endpoints: IdenticalEndpoints) extends SingleRelationship with CyclicConnection {

  override type SELF[XO, XE] = CyclicRelationship { type O = XO; type E = XE }

  protected def equalsIfNotEq(obj: scala.Any): Boolean = obj match {
    case other: CyclicRelationship => orientation.eqv(endpoints, other.endpoints)
    case _ => false
  }

  override def flip: CyclicRelationship = this
}

object VarLengthRelationship {
  val seed = "VarLengthRelationship".hashCode
}

sealed trait VarLengthRelationship extends Connection {
  override type SELF[XO, XE] <: VarLengthRelationship { type O = XO; type E = XE }
  final protected override def seed = VarLengthRelationship.seed

  def lower: Int
  def upper: Option[Int]
}

final case class DirectedVarLengthRelationship(endpoints: DifferentEndpoints, lower: Int, upper: Option[Int]) extends VarLengthRelationship with DirectedConnection {
  override type SELF[XO, XE] = DirectedVarLengthRelationship { type O = XO; type E = XE }

  override def flip: DirectedVarLengthRelationship = copy(endpoints.flip)

  override protected def equalsIfNotEq(obj: Any): Boolean = obj match {
    case other: DirectedVarLengthRelationship => orientation.eqv(endpoints, other.endpoints)
    case _ => false
  }
}

final case class UndirectedVarLengthRelationship(endpoints: DifferentEndpoints, lower: Int, upper: Option[Int]) extends VarLengthRelationship with UndirectedConnection {
  override type SELF[XO, XE] = UndirectedVarLengthRelationship { type O = XO; type E = XE }

  override def flip: UndirectedVarLengthRelationship = this

  override protected def equalsIfNotEq(obj: Any): Boolean = obj match {
    case other: UndirectedVarLengthRelationship => orientation.eqv(endpoints, other.endpoints)
    case _ => false
  }
}
