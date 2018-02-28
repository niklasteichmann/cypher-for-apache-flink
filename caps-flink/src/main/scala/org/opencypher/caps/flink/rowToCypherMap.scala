package org.opencypher.caps.flink

import org.apache.flink.types.Row
import org.opencypher.caps.api.types.{CTNode, CTRelationship}
import org.opencypher.caps.api.value.CypherValue
import org.opencypher.caps.api.value.CypherValue.{CypherMap, CypherNull, CypherValue}
import org.opencypher.caps.flink.value.{CAPFNode, CAPFRelationship}
import org.opencypher.caps.impl.exception.UnsupportedOperationException
import org.opencypher.caps.impl.table.RecordHeader
import org.opencypher.caps.ir.api.expr.Var

final case class rowToCypherMap(header: RecordHeader) extends (Row => CypherMap) {

  override def apply(row: Row): CypherMap = {
    val values = header.internalHeader.fields.map { field =>
      field.name -> constructValue(row, field)
    }.toSeq

    CypherMap(values: _*)
  }

  private def constructValue(row: Row, field: Var): CypherValue = {
    field.cypherType match {
      case _: CTNode =>
        collectNode(row, field)

      case _: CTRelationship =>
        collectRel(row, field)

      case _ =>
        val raw = row.getField(header.slotFor(field).index)
        CypherValue(raw)
    }
  }

  private def collectNode(row: Row, field: Var): CypherValue = {
    val idValue = row.getField(header.slotFor(field).index).asInstanceOf[Any]
    idValue match {
      case null         => CypherNull
      case id: Long     =>
        val labels = header
          .labelSlots(field)
          .mapValues { s =>
            row.getField(s.index).asInstanceOf[Boolean]
          }
          .collect {
            case (h, b) if b =>
              h.label.name
          }
          .toSet

        val properties = header
          .propertySlots(field)
          .mapValues { s =>
            CypherValue(row.getField(s.index))
          }
          .collect {
            case (p, v) if !v.isNull =>
              p.key.name -> v
          }

        CAPFNode(id, labels, properties)
      case invalidID => throw UnsupportedOperationException(s"CAPFNode ID has to be a Long instead of ${invalidID.getClass}")
    }
  }

  private def collectRel(row: Row, field: Var): CypherValue = {
    val idValue = row.getField(header.slotFor(field).index).asInstanceOf[Any]
    idValue match {
      case null         => CypherNull
      case id: Long     =>
        val source = row.getField(header.sourceNodeSlot(field).index).asInstanceOf[Long]
        val target = row.getField(header.targetNodeSlot(field).index).asInstanceOf[Long]
        val typ = row.getField(header.typeSlot(field).index).asInstanceOf[String]
        val properties = header
          .propertySlots(field)
          .mapValues { s =>
            CypherValue.apply(row.getField(s.index).asInstanceOf[Any])
          }
          .collect {
            case (p, v) if !v.isNull =>
              p.key.name -> v
          }

        CAPFRelationship(id, source, target, typ, properties)
      case invalidID => throw UnsupportedOperationException(s"CAPFRelationship ID has to be a Long instead of ${invalidID.getClass}")
    }
  }

}
