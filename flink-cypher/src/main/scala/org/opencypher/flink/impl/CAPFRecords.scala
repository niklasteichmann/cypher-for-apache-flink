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
package org.opencypher.flink.impl

import org.apache.flink.api.java.typeutils.RowTypeInfo
import org.apache.flink.api.scala._
import org.apache.flink.table.api.scala._
import org.apache.flink.table.api.Table
import org.apache.flink.table.api.scala._
import org.apache.flink.table.expressions._
import org.apache.flink.types.Row
import org.opencypher.flink.api.CAPFSession
import org.opencypher.flink.impl.table.FlinkCypherTable.FlinkTable
import org.opencypher.flink.impl.TableOps._
import org.opencypher.flink.impl.convert.FlinkConversions._
import org.opencypher.flink.impl.convert.rowToCypherMap
import org.opencypher.okapi.api.types._
import org.opencypher.okapi.api.value.CypherValue.{CypherMap, CypherValue}
import org.opencypher.okapi.impl.exception.UnsupportedOperationException
import org.opencypher.okapi.relational.api.io.EntityTable
import org.opencypher.okapi.relational.api.table.{RelationalCypherRecords, RelationalCypherRecordsFactory}
import org.opencypher.okapi.relational.impl.table._

case class CAPFRecordsFactory(implicit capf: CAPFSession) extends RelationalCypherRecordsFactory[FlinkTable] {

  override type Records = CAPFRecords

  override def unit(): CAPFRecords = {
    val initialTable = capf.tableEnv.fromDataSet(capf.env.fromCollection(Seq(EmptyRow())))
    CAPFRecords(RecordHeader.empty, initialTable)
  }

  override def empty(initialHeader: RecordHeader = RecordHeader.empty): CAPFRecords = {
    val initialTableSchema = initialHeader.toResolvedFieldReference

    implicit val rowTypeInfo = new RowTypeInfo(initialTableSchema.map(_.resultType).toArray, initialTableSchema.map(_.name).toArray)
    val initialTable = capf.tableEnv.fromDataSet(
      capf.env.fromCollection(List.empty[Row]),
      initialTableSchema.map(field => UnresolvedFieldReference(field.name)): _*
    )
    CAPFRecords(initialHeader, initialTable)
  }

  override def fromEntityTable(entityTable: EntityTable[FlinkTable]): CAPFRecords = {
    val withCypherCompatibleTypes = entityTable.table.table.withCypherCompatibleTypes
    CAPFRecords(entityTable.header, withCypherCompatibleTypes)
  }

  override def from(
    header: RecordHeader,
    table: FlinkTable,
    maybeDisplayNames: Option[Seq[String]]
  ): CAPFRecords = {
    val displayNames = maybeDisplayNames match {
      case s@Some(_) => s
      case None => Some(header.vars.map(_.withoutType).toSeq)
    }
    CAPFRecords(header, table, displayNames)
  }

  /**
    * Wraps a Flink table (Table) in a CAPFRecords, making it understandable by Cypher.
    *
    * @param table   table to wrap.
    * @param capf session to which the resulting CAPSRecords is tied.
    * @return a Cypher table.
    */
  private[flink] def wrap(table: Table)(implicit capf: CAPFSession): CAPFRecords = {
    val compatibleTable = table.withCypherCompatibleTypes
    CAPFRecords(compatibleTable.getSchema.toRecordHeader, compatibleTable)
  }

  private case class EmptyRow()
}

case class CAPFRecords(
  header: RecordHeader,
  table: FlinkTable,
  override val logicalColumns: Option[Seq[String]]= None
)(implicit val capf: CAPFSession) extends RelationalCypherRecords[FlinkTable] with RecordBehaviour {

  override type Records = CAPFRecords

  def flinkTable: Table = table.table

  override def cache(): CAPFRecords = throw new UnsupportedOperationException("cache()")

  override def toString: String = {
    if (header.isEmpty) {
      s"CAPFRecords.empty"
    } else {
      s"CAPFRecords(header: $header)"
    }
  }

//  verify()
//
//  override def from(header: RecordHeader, table: FlinkTable, displayNames: Option[Seq[String]]): CAPFRecords =
//    copy(header, table.table, displayNames)
//
//  override def relationalTable: FlinkTable = table
//
//  def explain = println(capf.tableEnv.explain(table))
//
//  def toTable(colNames: String*): Table = colNames match {
//    case Nil => table
//    case _ => relationalTable.table.select(colNames.map(UnresolvedFieldReference): _*)
//  }
//
//  def cache(): CAPFRecords = ???
//
//  def retag(replacements: Map[Int, Int]): CAPFRecords = {
//    val actualRetaggings = replacements.filterNot { case (from, to) => from == to }
//    val idColumns = header.idColumns()
//    val tableWithReplacedTags = idColumns.foldLeft(table) {
//      case (t, column) => t.safeReplaceTags(column, actualRetaggings)
//    }
//    copy(header, tableWithReplacedTags)
//  }
//
//  def retagVariable(v: Var, replacements: Map[Int, Int]): CAPFRecords = {
//    val columnsToUpdate = header.idColumns(v)
//    val updatedData = columnsToUpdate.foldLeft(table) { case (table, columnName) =>
//      table.safeReplaceTags(columnName, replacements)
//    }
//    copy(header)
//  }
//
//  protected val TRUE_LIT: Expression = Literal(true, Types.BOOLEAN)
//  protected val FALSE_LIT: Expression = Literal(false, Types.BOOLEAN)
//  protected val NULL_LIT: Expression = Null(Types.BOOLEAN)
//  protected val EMPTY_LIST: Expression = ArrayConstructor(Seq("0"))
//
//  def alignWith(v: Var, targetHeader: RecordHeader): CAPFRecords = {
//
//    val entityVars = header.entityVars
//
//    val oldEntity = entityVars.toSeq match {
//      case Seq(one) => one
//      case Nil => throw IllegalArgumentException("one entity in the record header", s"no entity in $header")
//      case _ => throw IllegalArgumentException("one entity in the record header", s"multiple entities in $header")
//    }
//
//    assert(header.ownedBy(oldEntity) == header.expressions, s"header describes more than one entity")
//
//    val entityLabels: Set[String] = oldEntity.cypherType match {
//      case CTNode(labels, _) => labels
//      case CTRelationship(types, _) => types
//      case _ => throw IllegalArgumentException("CTNode or CTRelationship", oldEntity.cypherType)
//    }
//
//    val updatedHeader = header
//      .withAlias(oldEntity as v)
//      .select(v)
//
//    val missingExpressions = targetHeader.expressions -- updatedHeader.expressions
//    val overlapExpressions = targetHeader.expressions -- missingExpressions
//
//    val dataWithColumnsRenamed = overlapExpressions.foldLeft(table) {
//      case (currentTable, expr) =>
//        val oldColumn = updatedHeader.column(expr)
//        val newColumn = targetHeader.column(expr)
//        if (oldColumn != newColumn) {
//          currentTable
//            .safeReplaceColumn(oldColumn, UnresolvedFieldReference(oldColumn).cast(expr.cypherType.toFlinkType.get) as Symbol(oldColumn))
//            .safeRenameColumn(oldColumn, newColumn)
//        } else {
//          currentTable
//        }
//    }
//
//    val dataWithMissingColumns = missingExpressions.foldLeft(dataWithColumnsRenamed) {
//      case (currentTable, expr) =>
//        val newColumn = expr match {
//          case HasLabel(_, label) => if (entityLabels.contains(label.name)) TRUE_LIT else FALSE_LIT
//          case HasType(_, relType) => if (entityLabels.contains(relType.name)) TRUE_LIT else FALSE_LIT
//          case _ =>
//            if (!expr.cypherType.isNullable) {
//              throw UnsupportedOperationException(
//                s"Cannot align scan on $v by adding a NULL column, because the type for '$expr' is non-nullable"
//              )
//            }
//            val nullOrEmptyList = if (!expr.cypherType.getFlinkType.isBasicType) {
//              EMPTY_LIST
//            } else {
//              NULL_LIT
//            }
//            nullOrEmptyList.cast(expr.cypherType.getFlinkType)
//        }
//
//        currentTable.safeUpsertColumn(
//          targetHeader.column(expr),
//          newColumn.as(Symbol(targetHeader.column(expr)))
//        )
//    }
//    copy(targetHeader, dataWithMissingColumns)
//  }
//
//  private def verify(): Unit = {
//
//    @tailrec
//    def containsEntity(t: CypherType): Boolean = t match {
//      case _: CTNode => true
//      case _: CTRelationship => true
//      case l: CTList => containsEntity(l.elementType)
//      case _ => false
//    }
//
//    val initialDataColumns = relationalTable.physicalColumns.toSeq
//
//    val duplicateColumns = initialDataColumns.groupBy(identity).collect {
//      case (key, values) if values.size > 1 => key
//    }
//
//    if (duplicateColumns.nonEmpty)
//      throw IllegalArgumentException(
//        "a DataFrame with distinct columns",
//        s"a DataFrame with duplicate columns: ${initialDataColumns.sorted.mkString("[", ", ", "]")}"
//      )
//
//    val headerColumnNames = header.columns
//    val dataColumnNames = relationalTable.physicalColumns.toSet
//    val missingColumnNames = headerColumnNames -- dataColumnNames
//    if (missingColumnNames.nonEmpty) {
//      throw IllegalArgumentException(
//        s"data with columns ${header.columns.toSeq.sorted.mkString("\n[", ", ", "]\n")}",
//        s"data with columns ${dataColumnNames.toSeq.sorted.mkString("\n[", ", ", "]\n")}"
//      )
//    }
//
//    header.expressions.foreach { expr =>
//      val tableSchema = table.getSchema
//      val field = header.column(expr)
//      val cypherType = tableSchema.getType(field).get.toCypherType().getOrElse(
//        throw IllegalArgumentException("a supported Flink type", field)
//      )
//      val headerType = expr.cypherType
//
//      if (headerType.toFlinkType != cypherType.toFlinkType && !containsEntity(headerType))
//        throw IllegalArgumentException(s"a valid data type for column $field of type $headerType", cypherType)
//    }
//  }
//
//  override def toString: String = {
//    val numRows = relationalTable.size
//    if (header.isEmpty && numRows == 0) {
//      s"CAPFRecords.empty"
//    } else if (header.isEmpty && numRows == 1) {
//      s"CAPFRecords.unit"
//    } else {
//      s"CAPFRecords($header, table with $numRows rows)"
//    }
//  }

}

trait RecordBehaviour extends RelationalCypherRecords[FlinkTable] {

  override lazy val columnType: Map[String, CypherType] = table.table.columnType

  override def rows: Iterator[String => CypherValue] = table.table.rows

  override def iterator: Iterator[CypherMap] = toCypherMaps.collect().iterator

  def toLocalIterator: Iterator[CypherMap] = iterator

  override def collect: Array[CypherMap] = toCypherMaps.collect().toArray

  def toCypherMaps: DataSet[CypherMap] = {
    table.table.toDataSet[Row].map(rowToCypherMap(header.exprToColumn.toSeq, table.table.getSchema.columnNameToIndex))
  }
}
