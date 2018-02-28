package org.opencypher.caps.flink

import org.apache.flink.api.scala._
import org.apache.flink.table.api.scala._
import org.apache.flink.types.Row
import org.opencypher.caps.api.io.conversion.{NodeMapping, RelationshipMapping}
import org.opencypher.caps.flink.schema._


object Demo extends App {

  val session = CAPFSession.create

  private val nodeDataSet = session.env.fromCollection(DemoData.nodes)
  val relsDataSet = session.env.fromCollection(DemoData.rels)

  val nodes = session.tableEnv.fromDataSet(nodeDataSet, 'ID, 'EMPLOYEE, 'NAME, 'AGE)
  val rels = session.tableEnv.fromDataSet(relsDataSet, 'ID, 'SOURCE, 'TARGET, 'TYPE, 'SINCE)

  val nodeMapping = NodeMapping
    .withSourceIdKey("ID")
    .withImpliedLabel("Person")
    .withOptionalLabel("Employee", "EMPLOYEE")
    .withPropertyKey("name", "NAME")
    .withPropertyKey("age", "AGE")

  val relMapping = RelationshipMapping
    .withSourceIdKey("ID")
    .withSourceStartNodeKey("SOURCE")
    .withSourceEndNodeKey("TARGET")
    .withSourceRelTypeKey("TYPE", Set("KNOWS"))
    .withPropertyKey("since", "SINCE")

  val nodeTable = CAPFNodeTable(nodeMapping, nodes)
  val relTable = CAPFRelationshipTable(relMapping, rels)

  val graph: CAPFGraph = session.readFrom(nodeTable, relTable)
  graph.nodes("p").data.toDataSet[Row].print()
  graph.relationships("r").data.toDataSet[Row].print()
}

object DemoData {
  val nodes = Seq(
    (0L, false, "Alice", 26),
    (1L, false, "Bob", 23),
    (3L, true, "Pete", 29)
  )

  val rels = Seq(
    (2L, 0L, 1L, "KNOWS", "2018")
  )

}
