/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.indexstatemanagement.migration

import org.junit.Assume
import org.opensearch.indexmanagement.indexstatemanagement.IndexStateManagementRestTestCase
import org.opensearch.indexmanagement.indexstatemanagement.randomPolicy
import org.opensearch.indexmanagement.indexstatemanagement.settings.LegacyOpenDistroManagedIndexSettings
import org.opensearch.indexmanagement.indexstatemanagement.settings.ManagedIndexSettings
import org.opensearch.indexmanagement.waitFor
import java.time.Instant

class MigrationServicesIT : IndexStateManagementRestTestCase() {
    fun `test v1 index templates different order migration`() {
        val policyID1 = "p1"
        val policyID2 = "p2"
        createPolicy(randomPolicy(), policyID1)
        createPolicy(randomPolicy(), policyID2)
        createV1Template("t1", "a*", policyID1, order = -1)
        createV1Template("t2", "ab*", policyID1)
        createV1Template("t3", "ab*", policyID2, order = 1)
        createV1Template2("t4", "ab*", order = 0)
        enableISMTemplateMigration()

        waitFor(Instant.ofEpochSecond(80)) {
            assertEquals(getPolicy(policyID2).ismTemplate?.first()?.indexPatterns.toString(), "[ab*]")
            assertEquals(getPolicy(policyID2).ismTemplate?.first()?.priority, 2)
        }

        // 1s interval to let the ism_template becomes searchable so that coordinator
        // can pick it up
        Thread.sleep(1_000)
        // need to delete overlapping template, otherwise warning will fail the test
        deleteV1Template("t1")
        deleteV1Template("t2")
        deleteV1Template("t3")
        deleteV1Template("t4")

        val indexName = "ab_index"
        createIndex(indexName, policyID = null)
        waitFor {
            assertPredicatesOnMetaData(
                listOf(
                    indexName to listOf(
                        ManagedIndexSettings.POLICY_ID.key to policyID2::equals,
                        LegacyOpenDistroManagedIndexSettings.POLICY_ID.key to policyID2::equals
                    )
                ),
                getExplainMap(indexName), false
            )
        }
    }

    fun `test v1 index templates migration`() {
        // cat/templates API could return template info in different order in multi-node test
        // so skip for multi-node test
        Assume.assumeFalse(isMultiNode)

        val policyID1 = "p1"
        val policyID2 = "p2"
        createPolicy(randomPolicy(), policyID1)
        createPolicy(randomPolicy(), policyID2)
        createV1Template("t1", "a*", policyID1)
        createV1Template("t2", "ab*", policyID2)
        enableISMTemplateMigration()

        // cat templates, check t1 t2 order
        val order = getTemplatesOrder()

        // t1, t2
        if (order == listOf("t1", "t2")) {
            waitFor(Instant.ofEpochSecond(80)) {
                assertEquals(getPolicy(policyID1).ismTemplate?.first()?.indexPatterns.toString(), "[a*]")
                assertEquals(getPolicy(policyID1).ismTemplate?.first()?.priority, 1)
                assertEquals(getPolicy(policyID2).ismTemplate?.first()?.indexPatterns.toString(), "[ab*]")
                assertEquals(getPolicy(policyID2).ismTemplate?.first()?.priority, 0)
            }
        }

        // t2, t1
        if (order == listOf("t2", "t1")) {
            waitFor(Instant.ofEpochSecond(80)) {
                waitFor(Instant.ofEpochSecond(80)) {
                    assertEquals(getPolicy(policyID1).ismTemplate?.first()?.indexPatterns.toString(), "[a*]")
                    assertEquals(getPolicy(policyID1).ismTemplate?.first()?.priority, 0)
                    assertEquals(getPolicy(policyID2).ismTemplate?.first()?.indexPatterns.toString(), "[ab*]")
                    assertEquals(getPolicy(policyID2).ismTemplate?.first()?.priority, 1)
                }
            }
        }

        // 1s interval to let the ism_template becomes searchable so that coordinator
        // can pick it up
        Thread.sleep(1_000)
        deleteV1Template("t1")
        deleteV1Template("t2")

        if (order == listOf("t1", "t2")) {
            val indexName = "ab_index"
            createIndex(indexName, policyID = null)
            waitFor {
                assertPredicatesOnMetaData(
                    listOf(
                        indexName to listOf(
                            ManagedIndexSettings.POLICY_ID.key to policyID1::equals,
                            LegacyOpenDistroManagedIndexSettings.POLICY_ID.key to policyID1::equals
                        )
                    ),
                    getExplainMap(indexName), false
                )
            }
        }

        if (order == listOf("t2", "t1")) {
            val indexName = "ab_index"
            createIndex(indexName, policyID = null)
            waitFor {
                assertPredicatesOnMetaData(
                    listOf(
                        indexName to listOf(
                            ManagedIndexSettings.POLICY_ID.key to policyID2::equals,
                            LegacyOpenDistroManagedIndexSettings.POLICY_ID.key to policyID2::equals
                        )
                    ),
                    getExplainMap(indexName), false
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getTemplatesOrder(): List<String?> {
        val order = catIndexTemplates().map {
            val row = it as Map<String, String>
            row["name"]
        }
        return order
    }

    fun `test v2 index templates migration`() {
        val policyID1 = "p1"
        createPolicy(randomPolicy(), policyID1)
        createV2Template("t1", "a*", policyID1)
        enableISMTemplateMigration()

        waitFor(Instant.ofEpochSecond(80)) {
            assertEquals(getPolicy(policyID1).ismTemplate?.first()?.indexPatterns.toString(), "[a*]")
        }

        // 1s interval to let the ism_template becomes searchable so that coordinator
        // can pick it up
        Thread.sleep(1_000)
        deleteV2Template("t1")

        val indexName = "ab_index"
        createIndex(indexName, policyID = null)
        waitFor {
            assertPredicatesOnMetaData(
                listOf(
                    indexName to listOf(
                        ManagedIndexSettings.POLICY_ID.key to policyID1::equals,
                        LegacyOpenDistroManagedIndexSettings.POLICY_ID.key to policyID1::equals
                    )
                ),
                getExplainMap(indexName), false
            )
        }
    }

    private fun enableISMTemplateMigration() {
        updateClusterSetting(ManagedIndexSettings.TEMPLATE_MIGRATION_CONTROL.key, "-1")
        updateClusterSetting(ManagedIndexSettings.TEMPLATE_MIGRATION_CONTROL.key, "0")
    }
}
