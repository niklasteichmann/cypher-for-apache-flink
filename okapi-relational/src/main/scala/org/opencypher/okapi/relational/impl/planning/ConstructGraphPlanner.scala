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
package org.opencypher.okapi.relational.impl.planning

import cats.data.NonEmptyList
import org.opencypher.okapi.api.graph.QualifiedGraphName
import org.opencypher.okapi.api.types.{CTBoolean, CTInteger, CTNode}
import org.opencypher.okapi.impl.exception.UnsupportedOperationException
import org.opencypher.okapi.ir.api.expr._
import org.opencypher.okapi.ir.api.set.{SetLabelItem, SetPropertyItem}
import org.opencypher.okapi.ir.api.{Label, PropertyKey, RelType, expr}
import org.opencypher.okapi.logical.impl._
import org.opencypher.okapi.relational.api.graph.RelationalCypherGraph
import org.opencypher.okapi.relational.api.planning.RelationalRuntimeContext
import org.opencypher.okapi.relational.api.table.Table
import org.opencypher.okapi.relational.api.tagging.TagSupport.computeRetaggings
import org.opencypher.okapi.relational.api.tagging.Tags
import org.opencypher.okapi.relational.api.tagging.Tags._
import org.opencypher.okapi.relational.impl.operators.{ConstructGraph, RelationalOperator}
import org.opencypher.okapi.relational.impl.planning.RelationalPlanner.RelationalOperatorOps
import org.opencypher.okapi.relational.impl.table.RecordHeader
import org.opencypher.okapi.relational.impl.{operators => relational}

import scala.reflect.runtime.universe.TypeTag

object ConstructGraphPlanner {

  def planConstructGraph[T <: Table[T] : TypeTag](inputTablePlan: RelationalOperator[T], construct: LogicalPatternGraph)
    (implicit context: RelationalRuntimeContext[T]): RelationalOperator[T] = {

    val onGraphPlan: RelationalOperator[T] = {
      construct.onGraphs match {
        case Nil => relational.Start[T](context.session.emptyGraphQgn) // Empty start
        case one :: Nil => // Just one graph, no union required
          relational.Start(one, tagStrategy = computeRetaggings(Map(one -> context.resolveGraph(one).tags)))
        case several =>
          val onGraphPlans = NonEmptyList.fromListUnsafe(several).map(qgn => relational.Start[T](qgn))
          relational.GraphUnionAll[T](onGraphPlans, construct.qualifiedGraphName)
      }
    }

    val onGraph = onGraphPlan.graph

    val unionTagStrategy: Map[QualifiedGraphName, Map[Int, Int]] = onGraphPlan.tagStrategy

    val LogicalPatternGraph(schema, clonedVarsToInputVars, createdEntities, sets, _, name) = construct

    val matchGraphs: Set[QualifiedGraphName] = clonedVarsToInputVars.values.map(_.cypherType.graph.get).toSet
    val allGraphs = unionTagStrategy.keySet ++ matchGraphs
    val tagsForGraph: Map[QualifiedGraphName, Set[Int]] = allGraphs.map(qgn => qgn -> context.resolveGraph(qgn).tags).toMap

    val constructTagStrategy = computeRetaggings(tagsForGraph, unionTagStrategy)

    // Apply aliases in CLONE to input table in order to create the base table, on which CONSTRUCT happens
    val aliasClones = clonedVarsToInputVars
      .filter { case (alias, original) => alias != original }
      .map(_.swap)

    val aliasOp: RelationalOperator[T] = if (aliasClones.isEmpty) {
      inputTablePlan
    } else {
      relational.Alias(inputTablePlan, aliasClones.map { case (expr, alias) => expr as alias }.toSeq)
    }

    val retagBaseTableOp = clonedVarsToInputVars.foldLeft(aliasOp) {
      case (op, (alias, original)) => op.retagVariable(alias, constructTagStrategy(original.cypherType.graph.get))
    }

    // Construct CREATEd entities
    val (createdEntityTags, constructedEntitiesOp) = {
      if (createdEntities.isEmpty) {
        Set.empty[Int] -> retagBaseTableOp
      } else {
        val createdEntityTag = pickFreeTag(constructTagStrategy)
        val entitiesOp = planConstructEntities(retagBaseTableOp, createdEntities, createdEntityTag)

        val setValueForExprTuples = sets.flatMap {
          case SetPropertyItem(propertyKey, v, valueExpr) =>
            List(valueExpr -> Property(v, PropertyKey(propertyKey))(valueExpr.cypherType))
          case SetLabelItem(v, labels) =>
            labels.toList.map { label =>
              v.cypherType.material match {
                case _: CTNode => TrueLit -> expr.HasLabel(v, Label(label))(CTBoolean)
                case other => throw UnsupportedOperationException(s"Cannot set a label on $other")
              }
            }
        }
        Set(createdEntityTag) -> entitiesOp.addInto(setValueForExprTuples: _*)
      }
    }

    // Remove all vars that were part the original pattern graph DF, except variables that were CLONEd without an alias
    val allInputVars = aliasOp.header.vars
    val originalVarsToKeep = clonedVarsToInputVars.keySet -- aliasClones.keySet
    val varsToRemoveFromTable = allInputVars -- originalVarsToKeep

    val patternGraphTableOp = constructedEntitiesOp.dropExprSet(varsToRemoveFromTable)

    val tagsUsed = constructTagStrategy.foldLeft(createdEntityTags) {
      case (tags, (qgn, remapping)) =>
        val remappedTags = tagsForGraph(qgn).map(remapping)
        tags ++ remappedTags
    }

    val patternGraph = context.session.graphs.singleTableGraph(patternGraphTableOp, schema, tagsUsed)

    val graph = if (onGraph == context.session.graphs.empty) {
      context.session.graphs.unionGraph(patternGraph)
    } else {
      context.session.graphs.unionGraph(Map(identityRetaggings(onGraph), identityRetaggings(patternGraph)))
    }

    val constructOp = ConstructGraph(inputTablePlan, graph, name, constructTagStrategy, construct, context)

    context.queryLocalCatalog += (construct.qualifiedGraphName -> graph)

    constructOp
  }

  def pickFreeTag(tagStrategy: Map[QualifiedGraphName, Map[Int, Int]]): Int = {
    val usedTags = tagStrategy.values.flatMap(_.values).toSet
    Tags.pickFreeTag(usedTags)
  }

  def identityRetaggings[T <: Table[T]](g: RelationalCypherGraph[T]): (RelationalCypherGraph[T], Map[Int, Int]) = {
    g -> g.tags.zip(g.tags).toMap
  }

  def planConstructEntities[T <: Table[T] : TypeTag](
    inOp: RelationalOperator[T],
    toCreate: Set[ConstructedEntity],
    createdEntityTag: Int
  ): RelationalOperator[T] = {

    // Construct nodes before relationships, as relationships might depend on nodes
    val nodes: Set[ConstructedNode] = toCreate.collect {
      case c: ConstructedNode if !inOp.header.vars.contains(c.v) => c
    }
    val rels: Set[ConstructedRelationship] = toCreate.collect {
      case r: ConstructedRelationship if !inOp.header.vars.contains(r.v) => r
    }

    val (_, nodesToCreate) = nodes.foldLeft(0 -> Seq.empty[(Expr, Expr)]) {
      case ((nextColumnPartitionId, nodeProjections), nextNodeToConstruct) =>
        (nextColumnPartitionId + 1) -> (nodeProjections ++ computeNodeProjections(inOp, createdEntityTag, nextColumnPartitionId, nodes.size, nextNodeToConstruct))
    }

    val createdNodesOp = inOp.addInto(nodesToCreate.map { case (into, value) => value -> into }: _*)

    val (_, relsToCreate) = rels.foldLeft(0 -> Seq.empty[(Expr, Expr)]) {
      case ((nextColumnPartitionId, relProjections), nextRelToConstruct) =>
        (nextColumnPartitionId + 1) ->
          (relProjections ++ computeRelationshipProjections(createdNodesOp, createdEntityTag, nextColumnPartitionId, rels.size, nextRelToConstruct))
    }

    createdNodesOp.addInto(relsToCreate.map { case (into, value) => value -> into }: _*)
  }

  def computeNodeProjections[T <: Table[T]](
    inOp: RelationalOperator[T],
    createdEntityTag: Int,
    columnIdPartition: Int,
    numberOfColumnPartitions: Int,
    node: ConstructedNode
  ): Map[Expr, Expr] = {

    val idTuple = node.v -> generateId(columnIdPartition, numberOfColumnPartitions).setTag(createdEntityTag)

    val copiedLabelTuples = node.baseEntity match {
      case Some(origNode) => copyExpressions(inOp, node.v)(_.labelsFor(origNode))
      case None => Map.empty
    }

    val createdLabelTuples = node.labels.map {
      label => HasLabel(node.v, label)(CTBoolean) -> TrueLit
    }.toMap

    val propertyTuples = node.baseEntity match {
      case Some(origNode) => copyExpressions(inOp, node.v)(_.propertiesFor(origNode))
      case None => Map.empty
    }

    createdLabelTuples ++
      copiedLabelTuples ++
      propertyTuples +
      idTuple
  }

  def computeRelationshipProjections[T <: Table[T]](
    inOp: RelationalOperator[T],
    createdEntityTag: Int,
    columnIdPartition: Int,
    numberOfColumnPartitions: Int,
    toConstruct: ConstructedRelationship
  ): Map[Expr, Expr] = {
    val ConstructedRelationship(rel, source, target, typOpt, baseRelOpt) = toConstruct

    // id needs to be generated
    val idTuple = rel -> generateId(columnIdPartition, numberOfColumnPartitions).setTag(createdEntityTag)

    // source and target are present: just copy
    val sourceTuple = {
      StartNode(rel)(CTInteger) -> source
    }
    val targetTuple = {
      EndNode(rel)(CTInteger) -> target
    }

    val typeTuple: Map[Expr, Expr] = {
      typOpt match {
        // type is set
        case Some(t) =>
          Map(HasType(rel, RelType(t))(CTBoolean) -> TrueLit)
        case None =>
          // When no type is present, it needs to be a copy of a base relationship
          copyExpressions(inOp, rel)(_.typesFor(baseRelOpt.get))
      }
    }

    val propertyTuples: Map[Expr, Expr] = baseRelOpt match {
      case Some(baseRel) =>
        copyExpressions(inOp, rel)(_.propertiesFor(baseRel))
      case None => Map.empty
    }

    propertyTuples ++ typeTuple + idTuple + sourceTuple + targetTuple
  }

  def copyExpressions[E <: Expr, T <: Table[T]](inOp: RelationalOperator[T], targetVar: Var)
    (extractor: RecordHeader => Set[E]): Map[Expr, Expr] = {
    val origExprs = extractor(inOp.header)
    val copyExprs = origExprs.map(_.withOwner(targetVar))
    copyExprs.zip(origExprs).toMap
  }

  // TODO: improve documentation and add specific tests
  def generateId(columnIdPartition: Int, numberOfColumnPartitions: Int): Expr = {
    val columnPartitionBits = math.log(numberOfColumnPartitions).floor.toInt + 1
    val totalIdSpaceBits = 33
    val columnIdShift = totalIdSpaceBits - columnPartitionBits

    // id needs to be generated
    // Limits the system to 500 mn partitions
    // The first half of the id space is protected
    val columnPartitionOffset = columnIdPartition.toLong << columnIdShift

    Add(MonotonicallyIncreasingId(), IntegerLit(columnPartitionOffset)(CTInteger))(CTInteger)
  }

}
