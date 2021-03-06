package org.opencypher.flink.test.fixture

import org.apache.flink.table.api.scala._
import org.apache.flink.table.expressions.UnresolvedFieldReference
import org.apache.flink.types.Row
import org.opencypher.flink.impl.CAPFRecords
import org.opencypher.flink.api.io.FlinkCypherTable._
import org.opencypher.flink.test.CAPFTestSuite
import org.opencypher.okapi.ir.api.expr.Expr
import org.opencypher.okapi.testing.Bag._

trait RecordsVerificationFixture {

  self: CAPFTestSuite =>

  protected def verify(records: CAPFRecords, extectedExprs: Seq[Expr], expectedData: Bag[Row]): Unit = {
    val table = records.table
    val header = records.header
    val expectedColumns = extectedExprs.map(header.column)
    table.physicalColumns.length should equal(expectedColumns.size)
    table.physicalColumns.toSet should equal(expectedColumns.toSet)
    table.select(expectedColumns.map(UnresolvedFieldReference): _*).collect().toArray.toBag should equal(expectedData)
  }

}
