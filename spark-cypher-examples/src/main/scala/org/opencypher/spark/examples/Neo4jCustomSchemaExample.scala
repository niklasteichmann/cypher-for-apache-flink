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
// tag::full-example[]
package org.opencypher.spark.examples

import org.opencypher.okapi.api.graph.Namespace
import org.opencypher.okapi.neo4j.io.testing.Neo4jHarnessUtils._
import org.opencypher.spark.api.{CAPSSession, GraphSources}
import org.opencypher.spark.testing.support.creation.CAPSNeo4jHarnessUtils._
import org.opencypher.spark.util.ConsoleApp

/**
  * Demonstrates connecting a graph from a CSV data source with a graph from a Neo4j data source.
  *
  * Writes updates back to the Neo4j database with Cypher queries.
  */
object Neo4jCustomSchemaExample extends ConsoleApp {
  // Create CAPS session
  implicit val session: CAPSSession = CAPSSession.local()

  // Start a Neo4j instance and populate it with social network data
  val neo4j = startNeo4j(personNetwork).withSchemaProcedure

  // Initialise schema from serialised file
  // This bypasses the automatic schema computation
  // Useful when the schema has already been calculated and stored in a file
  val schemaPath = getClass.getResource("/schema.json").getPath

  // Register Property Graph Data Sources (PGDS) with input schema file
  session.registerSource(Namespace("socialNetwork"), GraphSources.cypher.neo4j(neo4j.dataSourceConfig, schemaPath, false))

  // run queries!
  session.cypher(
    """
      |FROM GRAPH socialNetwork.graph
      |MATCH (a:Person)
      |WHERE a.age > 15
      |MATCH (a)-[:FRIEND_OF]->(b)
      |RETURN b.name
    """.stripMargin)
    .show

  // Shutdown Neo4j test instance
  neo4j.stop()

  def personNetwork =
    s"""|CREATE (a:Person { name: 'Alice', age: 10 })
        |CREATE (b:Person { name: 'Bob', age: 20})
        |CREATE (c:Person { name: 'Carol', age: 15})
        |CREATE (a)-[:FRIEND_OF { since: '23/01/1987' }]->(b)
        |CREATE (b)-[:FRIEND_OF { since: '12/12/2009' }]->(c)""".stripMargin
}
// end::full-example[]
