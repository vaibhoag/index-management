/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.indexmanagement.indexstatemanagement

import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.HttpEntity
import org.apache.hc.core5.http.HttpHeaders
import org.apache.hc.core5.http.io.entity.StringEntity
import org.apache.hc.core5.http.message.BasicHeader
import org.junit.After
import org.junit.Before
import org.opensearch.OpenSearchParseException
import org.opensearch.action.get.GetResponse
import org.opensearch.action.search.SearchResponse
import org.opensearch.client.Request
import org.opensearch.client.Response
import org.opensearch.client.ResponseException
import org.opensearch.client.RestClient
import org.opensearch.cluster.ClusterModule
import org.opensearch.cluster.metadata.IndexMetadata
import org.opensearch.common.settings.Settings
import org.opensearch.common.unit.TimeValue
import org.opensearch.common.xcontent.LoggingDeprecationHandler
import org.opensearch.common.xcontent.XContentType
import org.opensearch.common.xcontent.json.JsonXContent.jsonXContent
import org.opensearch.core.rest.RestStatus
import org.opensearch.core.xcontent.DeprecationHandler
import org.opensearch.core.xcontent.NamedXContentRegistry
import org.opensearch.core.xcontent.XContentParser.Token
import org.opensearch.core.xcontent.XContentParserUtils.ensureExpectedToken
import org.opensearch.index.seqno.SequenceNumbers
import org.opensearch.indexmanagement.IndexManagementIndices
import org.opensearch.indexmanagement.IndexManagementPlugin
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.INDEX_MANAGEMENT_INDEX
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.INDEX_STATE_MANAGEMENT_HISTORY_TYPE
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.ISM_BASE_URI
import org.opensearch.indexmanagement.IndexManagementPlugin.Companion.POLICY_BASE_URI
import org.opensearch.indexmanagement.IndexManagementRestTestCase
import org.opensearch.indexmanagement.indexstatemanagement.model.ChangePolicy
import org.opensearch.indexmanagement.indexstatemanagement.model.ExplainFilter
import org.opensearch.indexmanagement.indexstatemanagement.model.ISMTemplate
import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.Policy
import org.opensearch.indexmanagement.indexstatemanagement.model.Policy.Companion.POLICY_TYPE
import org.opensearch.indexmanagement.indexstatemanagement.model.StateFilter
import org.opensearch.indexmanagement.indexstatemanagement.resthandler.RestExplainAction
import org.opensearch.indexmanagement.indexstatemanagement.settings.ManagedIndexSettings
import org.opensearch.indexmanagement.indexstatemanagement.util.FAILED_INDICES
import org.opensearch.indexmanagement.indexstatemanagement.util.FAILURES
import org.opensearch.indexmanagement.indexstatemanagement.util.INDEX_NUMBER_OF_REPLICAS
import org.opensearch.indexmanagement.indexstatemanagement.util.INDEX_NUMBER_OF_SHARDS
import org.opensearch.indexmanagement.indexstatemanagement.util.UPDATED_INDICES
import org.opensearch.indexmanagement.makeRequest
import org.opensearch.indexmanagement.opensearchapi.parseWithType
import org.opensearch.indexmanagement.rollup.model.Rollup
import org.opensearch.indexmanagement.rollup.model.RollupMetadata
import org.opensearch.indexmanagement.rollup.randomTermQuery
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.ActionMetaData
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.PolicyRetryInfoMetaData
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.StateMetaData
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.StepMetaData
import org.opensearch.indexmanagement.spi.indexstatemanagement.model.ValidationResult
import org.opensearch.indexmanagement.transform.model.Transform
import org.opensearch.indexmanagement.transform.model.TransformMetadata
import org.opensearch.indexmanagement.util._ID
import org.opensearch.indexmanagement.util._PRIMARY_TERM
import org.opensearch.indexmanagement.util._SEQ_NO
import org.opensearch.indexmanagement.waitFor
import org.opensearch.jobscheduler.spi.schedule.IntervalSchedule
import org.opensearch.rest.RestRequest
import org.opensearch.search.SearchModule
import org.opensearch.test.OpenSearchTestCase
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.Locale

abstract class IndexStateManagementRestTestCase : IndexManagementRestTestCase() {
    @After
    fun clearIndicesAfterEachTest() {
        wipeAllIndices(skip = isBWCTest)
    }

    val explainResponseOpendistroPolicyIdSetting = "index.opendistro.index_state_management.policy_id"
    val explainResponseOpenSearchPolicyIdSetting = "index.plugins.index_state_management.policy_id"

    @Before
    protected fun disableIndexStateManagementJitter() {
        // jitter would add a test-breaking delay to the integration tests
        updateIndexStateManagementJitterSetting(0.0)
    }

    protected open fun disableValidationService() {
        updateValidationServiceSetting(false)
    }

    protected open fun enableValidationService() {
        updateValidationServiceSetting(true)
    }
    protected fun createPolicy(
        policy: Policy,
        policyId: String = OpenSearchTestCase.randomAlphaOfLength(10),
        refresh: Boolean = true,
        userClient: RestClient? = null,
    ): Policy {
        val response = createPolicyJson(policy.toJsonString(), policyId, refresh, userClient)

        val policyJson =
            jsonXContent
                .createParser(
                    NamedXContentRegistry.EMPTY,
                    LoggingDeprecationHandler.INSTANCE,
                    response.entity.content,
                ).map()
        val createdId = policyJson["_id"] as String
        assertEquals("policy ids are not the same", policyId, createdId)
        return policy.copy(
            id = createdId,
            seqNo = (policyJson["_seq_no"] as Int).toLong(),
            primaryTerm = (policyJson["_primary_term"] as Int).toLong(),
        )
    }

    protected fun createPolicyJson(
        policyString: String,
        policyId: String,
        refresh: Boolean = true,
        userClient: RestClient? = null,
    ): Response {
        val client = userClient ?: client()
        val response =
            client
                .makeRequest(
                    "PUT",
                    "$POLICY_BASE_URI/$policyId?refresh=$refresh",
                    emptyMap(),
                    StringEntity(policyString, ContentType.APPLICATION_JSON),
                )
        assertEquals("Unable to create a new policy", RestStatus.CREATED, response.restStatus())
        return response
    }

    protected fun createRandomPolicy(refresh: Boolean = true): Policy {
        val policy = randomPolicy()
        val policyId = createPolicy(policy, refresh = refresh).id
        return getPolicy(policyId = policyId)
    }

    protected fun getPolicy(
        policyId: String,
        header: BasicHeader = BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"),
    ): Policy {
        val response = client().makeRequest("GET", "$POLICY_BASE_URI/$policyId", null, header)
        assertEquals("Unable to get policy $policyId", RestStatus.OK, response.restStatus())

        val parser = createParser(XContentType.JSON.xContent(), response.entity.content)
        ensureExpectedToken(Token.START_OBJECT, parser.nextToken(), parser)

        lateinit var id: String
        var primaryTerm = SequenceNumbers.UNASSIGNED_PRIMARY_TERM
        var seqNo = SequenceNumbers.UNASSIGNED_SEQ_NO
        lateinit var policy: Policy

        while (parser.nextToken() != Token.END_OBJECT) {
            parser.nextToken()

            when (parser.currentName()) {
                _ID -> id = parser.text()
                _SEQ_NO -> seqNo = parser.longValue()
                _PRIMARY_TERM -> primaryTerm = parser.longValue()
                POLICY_TYPE -> policy = Policy.parse(parser)
            }
        }
        return policy.copy(id = id, seqNo = seqNo, primaryTerm = primaryTerm)
    }

    protected fun removePolicy(index: String): Response {
        val response =
            client()
                .makeRequest("POST", "$ISM_BASE_URI/remove/$index")
        assertEquals("Request failed", RestStatus.OK, response.restStatus())
        return response
    }

    protected fun createIndex(
        index: String = randomAlphaOfLength(10).lowercase(Locale.ROOT),
        policyID: String? = randomAlphaOfLength(10),
        alias: String? = null,
        replicas: String? = null,
        shards: String? = null,
        mapping: String = "",
        settings: Settings? = null,
    ): Pair<String, String?> {
        val waitForActiveShards = if (isMultiNode) "all" else "1"
        val builtSettings =
            Settings.builder().let {
                if (alias == null) {
                    it.putNull(ManagedIndexSettings.ROLLOVER_ALIAS.key)
                } else {
                    it.put(ManagedIndexSettings.ROLLOVER_ALIAS.key, alias)
                }
                it.put(INDEX_NUMBER_OF_REPLICAS, replicas ?: "1")
                it.put(INDEX_NUMBER_OF_SHARDS, shards ?: "1")
                it.put("index.write.wait_for_active_shards", waitForActiveShards)
                if (settings != null) it.put(settings)
                it
            }.build()
        val aliases = if (alias == null) "" else "\"$alias\": { \"is_write_index\": true }"
        createIndex(index, builtSettings, mapping, aliases)
        if (policyID != null) {
            addPolicyToIndex(index, policyID)
        }
        return index to policyID
    }

    protected fun createDataStream(
        dataStream: String,
        template: StringEntity? = null,
    ) {
        val dataStreamTemplate =
            template ?: StringEntity(
                """
                {
                    "data_stream": {},
                    "index_patterns": ["$dataStream"]
                }
                """.trimIndent(),
                ContentType.APPLICATION_JSON,
            )
        val res =
            client().makeRequest(
                "PUT",
                "/_index_template/transform-data-stream-template",
                dataStreamTemplate,
            )
        assertEquals("Unexpected RestStatus", RestStatus.OK, res.restStatus())
        val response = client().makeRequest("PUT", "/_data_stream/$dataStream")
        assertEquals("Unexpected RestStatus", RestStatus.OK, response.restStatus())
    }

    protected fun changeAlias(
        index: String,
        alias: String,
        action: String = "remove",
        isWriteIndex: Boolean = false,
        routing: Int? = null,
        searchRouting: Int = randomInt(),
        indexRouting: Int = randomInt(),
        filter: String = randomTermQuery().toString(),
        isHidden: Boolean = randomBoolean(),
    ) {
        val isWriteIndexField = if (isWriteIndex) "\",\"is_write_index\": \"$isWriteIndex" else ""
        val params =
            if (action == "add" && routing != null) {
                """
                ,"routing": $routing,
                "search_routing": $searchRouting,
                "index_routing": $indexRouting,
                "filter": $filter,
                "is_hidden": $isHidden
                """.trimIndent()
            } else {
                ""
            }
        val body =
            """
            {
              "actions": [
                {
                  "$action": {
                    "index": "$index",
                    "alias": "$alias$isWriteIndexField"$params
                  }
                }
              ]
            }
            """.trimIndent()
        val response = client().makeRequest("POST", "_aliases", StringEntity(body, ContentType.APPLICATION_JSON))
        assertEquals("Unexpected RestStatus", RestStatus.OK, response.restStatus())
    }

    /** Refresh indices in the cluster */
    protected fun refresh(target: String = "_all") {
        val request = Request("POST", "/$target/_refresh")
        client().performRequest(request)
    }

    protected fun addPolicyToIndex(
        index: String,
        policyID: String,
    ) {
        val body =
            """
            {
              "policy_id": "$policyID"
            }
            """.trimIndent()
        val response = client().makeRequest("POST", "/_opendistro/_ism/add/$index", StringEntity(body, ContentType.APPLICATION_JSON))
        assertEquals("Unexpected RestStatus", RestStatus.OK, response.restStatus())
    }

    protected fun removePolicyFromIndex(index: String) {
        client().makeRequest("POST", "/_opendistro/_ism/remove/$index")
    }

    protected fun getPolicyIDOfManagedIndex(index: String): String? {
        val managedIndex = getManagedIndexConfig(index)
        return managedIndex?.policyID
    }

    protected fun updateClusterSetting(key: String, value: String, escapeValue: Boolean = true) {
        val formattedValue = if (escapeValue) "\"$value\"" else value
        val request =
            """
            {
                "persistent": {
                    "$key": $formattedValue
                }
            }
            """.trimIndent()
        val res =
            client().makeRequest(
                "PUT", "_cluster/settings", emptyMap(),
                StringEntity(request, ContentType.APPLICATION_JSON),
            )
        assertEquals("Request failed", RestStatus.OK, res.restStatus())
    }

    protected fun updateIndexStateManagementJitterSetting(value: Double) {
        updateClusterSetting(ManagedIndexSettings.JITTER.key, value.toString(), false)
    }

    protected fun updateValidationServiceSetting(value: Boolean) {
        updateClusterSetting(ManagedIndexSettings.ACTION_VALIDATION_ENABLED.key, value.toString(), false)
    }

    protected fun isIndexRemote(indexName: String): Boolean {
        val response = client().makeRequest("GET", "/$indexName/_settings")
        assertEquals("Unable to get index settings", RestStatus.OK, response.restStatus())
        val settingsMap = response.asMap()
        val indexSettings = (settingsMap[indexName] as Map<*, *>)["settings"] as Map<*, *>
        val remoteSetting = ((indexSettings["index"] as Map<*, *>)["store"] as Map<*, *>)["type"]?.equals("remote_snapshot")
        return remoteSetting ?: false
    }

    protected fun getDoc(indexName: String, docId: String): Map<*, *>? {
        try {
            val response = client().makeRequest("GET", "/$indexName/_doc/$docId")
            if (response.restStatus() == RestStatus.OK) {
                val responseMap = response.asMap()
                val source = responseMap["_source"] as Map<*, *>
                return source
            }
        } catch (e: ResponseException) {
            if (e.response.restStatus() != RestStatus.NOT_FOUND) {
                throw e
            }
        }
        return null
    }

    protected fun indexDoc(index: String, id: String? = null, source: String) {
        val endpoint = if (id != null) "/$index/_doc/$id" else "/$index/_doc"
        val response = client().makeRequest(
            "POST",
            endpoint,
            emptyMap(),
            StringEntity(source, ContentType.APPLICATION_JSON),
        )
        assertEquals("Failed to index document", RestStatus.CREATED, response.restStatus())
    }

    protected fun updateIndexSetting(
        index: String,
        key: String,
        value: String,
    ) {
        val body =
            """
            {
              "$key" : "$value"
            }
            """.trimIndent()
        val res =
            adminClient().makeRequest(
                "PUT", "$index/_settings", emptyMap(),
                StringEntity(body, ContentType.APPLICATION_JSON),
            )
        assertEquals("Update index setting failed", RestStatus.OK, res.restStatus())
    }

    protected fun getIndexSetting(index: String) {
        val res =
            client().makeRequest(
                "GET", "$index/_settings", emptyMap(),
            )
        assertEquals("Update index setting failed", RestStatus.OK, res.restStatus())
    }

    protected fun getManagedIndexConfig(index: String): ManagedIndexConfig? {
        val request =
            """
            {
                "seq_no_primary_term": true,
                "query": {
                    "term": {
                        "${ManagedIndexConfig.MANAGED_INDEX_TYPE}.${ManagedIndexConfig.INDEX_FIELD}": "$index"
                    }
                }
            }
            """.trimIndent()
        val response =
            adminClient().makeRequest(
                "POST", "$INDEX_MANAGEMENT_INDEX/_search", emptyMap(),
                StringEntity(request, ContentType.APPLICATION_JSON),
            )
        assertEquals("Request failed", RestStatus.OK, response.restStatus())
        val searchResponse = SearchResponse.fromXContent(createParser(jsonXContent, response.entity.content))
        assertTrue("Found more than one managed index config", searchResponse.hits.hits.size < 2)
        val hit = searchResponse.hits.hits.firstOrNull()
        return hit?.run {
            val xcp = createParser(jsonXContent, this.sourceRef)
            xcp.parseWithType(id, seqNo, primaryTerm, ManagedIndexConfig.Companion::parse)
        }
    }

    protected fun getManagedIndexConfigByDocId(id: String): ManagedIndexConfig? {
        val response = adminClient().makeRequest("GET", "$INDEX_MANAGEMENT_INDEX/_doc/$id")
        assertEquals("Request failed", RestStatus.OK, response.restStatus())
        val getResponse = GetResponse.fromXContent(createParser(jsonXContent, response.entity.content))
        assertTrue("Did not find managed index config", getResponse.isExists)
        return getResponse?.run {
            val xcp = createParser(jsonXContent, sourceAsBytesRef)
            xcp.parseWithType(id, seqNo, primaryTerm, ManagedIndexConfig.Companion::parse)
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getHistorySearchResponse(index: String): SearchResponse {
        val request =
            """
            {
                "seq_no_primary_term": true,
                "sort": [
                    {"$INDEX_STATE_MANAGEMENT_HISTORY_TYPE.history_timestamp": {"order": "desc"}}
                ],
                "query": {
                    "term": {
                        "$INDEX_STATE_MANAGEMENT_HISTORY_TYPE.index": "$index"
                    }
                }
            }
            """.trimIndent()
        val response =
            adminClient().makeRequest(
                "POST", "${IndexManagementIndices.HISTORY_ALL}/_search", emptyMap(),
                StringEntity(request, ContentType.APPLICATION_JSON),
            )
        assertEquals("Request failed", RestStatus.OK, response.restStatus())
        return SearchResponse.fromXContent(createParser(jsonXContent, response.entity.content))
    }

    protected fun getLatestHistory(searchResponse: SearchResponse): ManagedIndexMetaData {
        val hit = searchResponse.hits.hits.first()
        return hit.run {
            val xcp = createParser(jsonXContent, this.sourceRef)
            xcp.nextToken()
            ManagedIndexMetaData.parse(xcp)
        }
    }

    protected fun getExistingManagedIndexConfig(index: String): ManagedIndexConfig = waitFor {
        val config = getManagedIndexConfig(index)
        assertNotNull("ManagedIndexConfig is null", config)
        config!!
    }

    protected fun updateManagedIndexConfigStartTime(update: ManagedIndexConfig, desiredStartTimeMillis: Long? = null, retryOnConflict: Int = 0) {
        // Before updating start time of a job always make sure there are no unassigned shards that could cause the config
        // index to move to a new node and negate this forced start
        if (isMultiNode) {
            waitFor {
                try {
                    client().makeRequest(
                        "GET",
                        "_cluster/allocation/explain",
                        StringEntity("{ \"index\": \"$INDEX_MANAGEMENT_INDEX\" }", ContentType.APPLICATION_JSON),
                    )
                    fail("Expected 400 Bad Request when there are no unassigned shards to explain")
                } catch (e: ResponseException) {
                    assertEquals(RestStatus.BAD_REQUEST, e.response.restStatus())
                }
            }
        }
        val intervalSchedule = (update.jobSchedule as IntervalSchedule)
        val millis = Duration.of(intervalSchedule.interval.toLong(), intervalSchedule.unit).minusSeconds(2).toMillis()
        val startTimeMillis = desiredStartTimeMillis ?: Instant.now().toEpochMilli() - millis
        val waitForActiveShards = if (isMultiNode) "all" else "1"
        val endpoint = "$INDEX_MANAGEMENT_INDEX/_update/${update.id}?wait_for_active_shards=$waitForActiveShards;retry_on_conflict=$retryOnConflict"
        val response =
            adminClient().makeRequest(
                "POST", endpoint,
                StringEntity(
                    "{\"doc\":{\"managed_index\":{\"schedule\":{\"interval\":{\"start_time\":" +
                        "\"$startTimeMillis\"}}}}}",
                    ContentType.APPLICATION_JSON,
                ),
            )

        assertEquals("Request failed", RestStatus.OK, response.restStatus())
    }

    protected fun updateManagedIndexConfigPolicySeqNo(update: ManagedIndexConfig) {
        val response =
            adminClient().makeRequest(
                "POST", "$INDEX_MANAGEMENT_INDEX/_update/${update.id}",
                StringEntity(
                    "{\"doc\":{\"managed_index\":{\"policy_seq_no\":\"${update.policySeqNo}\"}}}",
                    ContentType.APPLICATION_JSON,
                ),
            )
        assertEquals("Request failed", RestStatus.OK, response.restStatus())
    }

    protected fun Policy.toHttpEntity(): HttpEntity = StringEntity(toJsonString(), ContentType.APPLICATION_JSON)

    protected fun ManagedIndexConfig.toHttpEntity(): HttpEntity = StringEntity(toJsonString(), ContentType.APPLICATION_JSON)

    protected fun ExplainFilter.toHttpEntity(): HttpEntity = StringEntity(toJsonString(), ContentType.APPLICATION_JSON)

    protected fun ChangePolicy.toHttpEntity(): HttpEntity {
        var string = "{\"${ChangePolicy.POLICY_ID_FIELD}\":\"$policyID\","
        if (state != null) {
            string += "\"${ChangePolicy.STATE_FIELD}\":\"$state\","
        }
        string += "\"${ChangePolicy.INCLUDE_FIELD}\":${include.map { "{\"${StateFilter.STATE_FIELD}\":\"${it.state}\"}" }}}"

        return StringEntity(string, ContentType.APPLICATION_JSON)
    }

    // Useful settings when debugging to prevent timeouts
    override fun restClientSettings(): Settings = if (isDebuggingTest || isDebuggingRemoteCluster) {
        Settings.builder()
            .put(CLIENT_SOCKET_TIMEOUT, TimeValue.timeValueMinutes(10))
            .build()
    } else {
        super.restClientSettings()
    }

    // Validate segment count per shard by specifying the min and max it should be
    @Suppress("UNCHECKED_CAST", "ReturnCount")
    protected fun validateSegmentCount(index: String, min: Int? = null, max: Int? = null): Boolean {
        require(min != null || max != null) { "Must provide at least a min or max" }
        val statsResponse: Map<String, Any> = getShardSegmentStats(index)

        val indicesStats = statsResponse["indices"] as Map<String, Map<String, Map<String, List<Map<String, Map<String, Any?>>>>>>
        return indicesStats[index]!!["shards"]!!.values.all { list ->
            list.filter { it["routing"]!!["primary"] == true }.all {
                logger.info("Checking primary shard segments for $it")
                if (it["routing"]!!["state"] != "STARTED") {
                    false
                } else {
                    val count = it["segments"]!!["count"] as Int
                    if (min != null && count < min) return false
                    if (max != null && count > max) return false
                    return true
                }
            }
        }
    }

    /** Get shard segment stats for [index] */
    private fun getShardSegmentStats(index: String): Map<String, Any> {
        val response = client().makeRequest("GET", "/$index/_stats/segments?level=shards")

        assertEquals("Stats request failed", RestStatus.OK, response.restStatus())

        return response.asMap()
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getIndexState(indexName: String): String {
        val request = Request("GET", "/_cluster/state")
        val response = client().performRequest(request)

        val responseMap = response.asMap()
        val metadata = responseMap["metadata"] as Map<String, Any>
        val indexMetaData = metadata["indices"] as Map<String, Any>
        val myIndex = indexMetaData[indexName] as Map<String, Any>
        return myIndex["state"] as String
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getIndexNamesOfPattern(pattern: String): Set<String> {
        val request = Request("GET", "/_cluster/state")
        val response = client().performRequest(request)

        val responseMap = response.asMap()
        val metadata = responseMap["metadata"] as Map<String, Any>
        val indexMetaData = metadata["indices"] as Map<String, Any>
        return indexMetaData.filter { it.key.startsWith(pattern) }.keys
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getIndexBlocksWriteSetting(indexName: String): String {
        val indexSettings = getIndexSettings(indexName) as Map<String, Map<String, Map<String, Any?>>>
        return indexSettings[indexName]!!["settings"]!![IndexMetadata.SETTING_BLOCKS_WRITE] as String
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getNumberOfReplicasSetting(indexName: String): Int {
        val indexSettings = getIndexSettings(indexName) as Map<String, Map<String, Map<String, Any?>>>
        return (indexSettings[indexName]!!["settings"]!![INDEX_NUMBER_OF_REPLICAS] as String).toInt()
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getNumberOfShardsSetting(indexName: String): Int {
        val indexSettings = getIndexSettings(indexName) as Map<String, Map<String, Map<String, Any?>>>
        return (indexSettings[indexName]!!["settings"]!![INDEX_NUMBER_OF_SHARDS] as String).toInt()
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getIndexPrioritySetting(indexName: String): Int {
        val indexSettings = getIndexSettings(indexName) as Map<String, Map<String, Map<String, Any?>>>
        return (indexSettings[indexName]!!["settings"]!!["index.priority"] as String).toInt()
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getIndexAutoManageSetting(indexName: String): Boolean? {
        val indexSettings = getIndexSettings(indexName) as Map<String, Map<String, Map<String, Any?>>>
        val autoManageSetting = indexSettings[indexName]!!["settings"]!!["index.plugins.index_state_management.auto_manage"]
        if (autoManageSetting != null) return (autoManageSetting as String).toBoolean()
        return null
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getIndexReadOnlySetting(indexName: String): Boolean? {
        val indexSettings = getIndexSettings(indexName) as Map<String, Map<String, Map<String, Any?>>>
        val readOnlySetting = indexSettings[indexName]!!["settings"]!![IndexMetadata.SETTING_READ_ONLY]
        if (readOnlySetting != null) return (readOnlySetting as String).toBoolean()
        return null
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getIndexReadOnlyAllowDeleteSetting(indexName: String): Boolean? {
        val indexSettings = getIndexSettings(indexName) as Map<String, Map<String, Map<String, Any?>>>
        val readOnlyAllowDeleteSetting = indexSettings[indexName]!!["settings"]!![IndexMetadata.SETTING_READ_ONLY_ALLOW_DELETE]
        if (readOnlyAllowDeleteSetting != null) return (readOnlyAllowDeleteSetting as String).toBoolean()
        return null
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getUuid(indexName: String): String {
        val indexSettings = getIndexSettings(indexName) as Map<String, Map<String, Map<String, Any?>>>
        return indexSettings[indexName]!!["settings"]!!["index.uuid"] as String
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getFlatSettings(indexName: String) =
        (getIndexSettings(indexName) as Map<String, Map<String, Map<String, Any?>>>)[indexName]!!["settings"] as Map<String, String>

    protected fun getExplainMap(indexName: String?, queryParams: String = ""): Map<String, Any> {
        var endpoint = RestExplainAction.EXPLAIN_BASE_URI
        if (indexName != null) endpoint += "/$indexName"
        if (queryParams.isNotEmpty()) endpoint += "?$queryParams"
        val response = client().makeRequest(RestRequest.Method.GET.toString(), endpoint)
        assertEquals("Unexpected RestStatus", RestStatus.OK, response.restStatus())
        return response.asMap()
    }

    @Suppress("UNCHECKED_CAST")
    protected fun getIndexShardNodes(indexName: String): List<Any> = getIndexShards(indexName).map { element -> (element as Map<String, String>)["node"]!! }

    @Suppress("UNCHECKED_CAST")
    protected fun getIndexShards(indexName: String): List<Any> = getShardsList().filter { element -> (element as Map<String, String>)["index"]!!.contains(indexName) }

    @Suppress("UNCHECKED_CAST")
    protected fun getNodes(): MutableSet<String> {
        val response =
            client()
                .makeRequest(
                    "GET",
                    "_cat/nodes?format=json",
                    emptyMap(),
                )
        assertEquals("Unable to get nodes", RestStatus.OK, response.restStatus())
        try {
            return jsonXContent
                .createParser(NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION, response.entity.content)
                .use { parser -> parser.list() }.map { element -> (element as Map<String, String>)["name"]!! }.toMutableSet()
        } catch (e: IOException) {
            throw OpenSearchParseException("Failed to parse content to list", e)
        }
    }

    // Calls explain API for a single concrete index and converts the response into a ManagedIndexMetaData
    // This only works for indices with a ManagedIndexMetaData that has been initialized
    @Suppress("LoopWithTooManyJumpStatements")
    protected fun getExplainManagedIndexMetaData(indexName: String, userClient: RestClient? = null): ManagedIndexMetaData {
        if (indexName.contains("*") || indexName.contains(",")) {
            throw IllegalArgumentException("This method is only for a single concrete index")
        }
        val client = userClient ?: client()
        val response = client.makeRequest(RestRequest.Method.GET.toString(), "${RestExplainAction.EXPLAIN_BASE_URI}/$indexName")
        assertEquals("Unexpected RestStatus", RestStatus.OK, response.restStatus())
        lateinit var metadata: ManagedIndexMetaData
        val xcp = createParser(XContentType.JSON.xContent(), response.entity.content)
        ensureExpectedToken(Token.START_OBJECT, xcp.nextToken(), xcp)
        while (xcp.nextToken() != Token.END_OBJECT) {
            val cn = xcp.currentName()
            xcp.nextToken()
            if (cn == "total_managed_indices") continue

            metadata = ManagedIndexMetaData.parse(xcp)
            break // bypass roles field
        }

        // make sure metadata is initialised
        assertTrue(metadata.transitionTo != null || metadata.stateMetaData != null || metadata.info != null || metadata.policyCompleted != null)
        return metadata
    }

    // Calls explain API for a single concrete index and converts the response into a ValidationResponse
    // This only works for indices with a ManagedIndexMetaData that has been initialized
    @Suppress("LoopWithTooManyJumpStatements")
    protected fun getExplainValidationResult(indexName: String): ValidationResult {
        if (indexName.contains("*") || indexName.contains(",")) {
            throw IllegalArgumentException("This method is only for a single concrete index")
        }

        val response = client().makeRequest(RestRequest.Method.GET.toString(), "${RestExplainAction.EXPLAIN_BASE_URI}/$indexName?validate_action=true")

        assertEquals("Unexpected RestStatus", RestStatus.OK, response.restStatus())
        lateinit var validationResult: ValidationResult
        val xcp = createParser(XContentType.JSON.xContent(), response.entity.content)
        ensureExpectedToken(Token.START_OBJECT, xcp.nextToken(), xcp)
        while (xcp.nextToken() != Token.END_OBJECT) {
            val cn = xcp.currentName()
            if (cn == "total_managed_indices") continue

            xcp.nextToken() // going into start object
            // loop next token until you find currentName == validate
            while (true) {
                val cn2 = xcp.currentName()
                if (cn2 == "validate") {
                    ensureExpectedToken(Token.START_OBJECT, xcp.nextToken(), xcp)
                    validationResult = ValidationResult.parse(xcp)
                    break
                }
                xcp.nextToken()
            }
            break // bypass roles field
        }
        return validationResult
    }

    protected fun rolloverIndex(alias: String) {
        val response =
            client().performRequest(
                Request(
                    "POST",
                    "/$alias/_rollover",
                ),
            )
        assertEquals(response.statusLine.statusCode, RestStatus.OK.status)
    }

    protected fun createRepository(
        repository: String,
    ) {
        val response =
            client()
                .makeRequest(
                    "PUT",
                    "_snapshot/$repository",
                    emptyMap(),
                    StringEntity("{\"type\":\"fs\", \"settings\": {\"location\": \"$repository\"}}", ContentType.APPLICATION_JSON),
                )
        assertEquals("Unable to create a new repository", RestStatus.OK, response.restStatus())
    }

    protected fun getShardsList(target: String = "*"): List<Any> {
        val response =
            client()
                .makeRequest(
                    "GET",
                    "_cat/shards/$target?format=json",
                    emptyMap(),
                )
        assertEquals("Unable to get allocation info", RestStatus.OK, response.restStatus())
        try {
            return jsonXContent
                .createParser(NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION, response.entity.content)
                .use { parser -> parser.list() }
        } catch (e: IOException) {
            throw OpenSearchParseException("Failed to parse content to list", e)
        }
    }

    protected fun cat(endpoint: String = "indices"): List<Any> {
        val response =
            client()
                .makeRequest(
                    "GET",
                    "_cat/$endpoint",
                    emptyMap(),
                )
        assertEquals("Unable to get cat info", RestStatus.OK, response.restStatus())
        try {
            return jsonXContent
                .createParser(NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION, response.entity.content)
                .use { parser -> parser.list() }
        } catch (e: IOException) {
            throw OpenSearchParseException("Failed to parse content to list", e)
        }
    }

    protected fun forceMerge(target: String, maxNumSegments: String) {
        val response = client().makeRequest("POST", "$target/_forcemerge?max_num_segments=$maxNumSegments")
        assertEquals("Unable to get cat info", RestStatus.OK, response.restStatus())
    }

    protected fun stats(target: String? = null, metrics: String? = null): Map<String, Any> {
        val endpoint = "${target ?: ""}/_stats${if (metrics == null) "" else "/$metrics"}"
        val response = client().makeRequest("GET", endpoint, emptyMap())
        assertEquals("Unable to get a stats", RestStatus.OK, response.restStatus())
        return response.asMap()
    }

    private fun getSnapshotsList(repository: String): List<Any> {
        val response =
            client()
                .makeRequest(
                    "GET",
                    "_cat/snapshots/$repository?format=json",
                    emptyMap(),
                )
        assertEquals("Unable to get a snapshot", RestStatus.OK, response.restStatus())
        try {
            return jsonXContent
                .createParser(NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION, response.entity.content)
                .use { parser -> parser.list() }
        } catch (e: IOException) {
            throw OpenSearchParseException("Failed to parse content to list", e)
        }
    }

    protected fun getRollup(
        rollupId: String,
        header: BasicHeader = BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"),
    ): Rollup {
        val response = client().makeRequest("GET", "${IndexManagementPlugin.ROLLUP_JOBS_BASE_URI}/$rollupId", null, header)
        assertEquals("Unable to get rollup $rollupId", RestStatus.OK, response.restStatus())

        val parser = createParser(XContentType.JSON.xContent(), response.entity.content)
        ensureExpectedToken(Token.START_OBJECT, parser.nextToken(), parser)

        lateinit var id: String
        var primaryTerm = SequenceNumbers.UNASSIGNED_PRIMARY_TERM
        var seqNo = SequenceNumbers.UNASSIGNED_SEQ_NO
        lateinit var rollup: Rollup

        while (parser.nextToken() != Token.END_OBJECT) {
            parser.nextToken()

            when (parser.currentName()) {
                _ID -> id = parser.text()
                _SEQ_NO -> seqNo = parser.longValue()
                _PRIMARY_TERM -> primaryTerm = parser.longValue()
                Rollup.ROLLUP_TYPE -> rollup = Rollup.parse(parser, id, seqNo, primaryTerm)
            }
        }
        return rollup
    }

    protected fun getRollupMetadata(
        metadataId: String,
        header: BasicHeader = BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"),
    ): RollupMetadata {
        val response = adminClient().makeRequest("GET", "$INDEX_MANAGEMENT_INDEX/_doc/$metadataId", null, header)
        assertEquals("Unable to get rollup metadata $metadataId", RestStatus.OK, response.restStatus())

        val parser = createParser(XContentType.JSON.xContent(), response.entity.content)
        ensureExpectedToken(Token.START_OBJECT, parser.nextToken(), parser)

        lateinit var id: String
        var primaryTerm = SequenceNumbers.UNASSIGNED_PRIMARY_TERM
        var seqNo = SequenceNumbers.UNASSIGNED_SEQ_NO
        lateinit var metadata: RollupMetadata

        while (parser.nextToken() != Token.END_OBJECT) {
            parser.nextToken()

            when (parser.currentName()) {
                _ID -> id = parser.text()
                _SEQ_NO -> seqNo = parser.longValue()
                _PRIMARY_TERM -> primaryTerm = parser.longValue()
                RollupMetadata.ROLLUP_METADATA_TYPE -> metadata = RollupMetadata.parse(parser, id, seqNo, primaryTerm)
            }
        }

        return metadata
    }

    protected fun getTransform(
        transformId: String,
        header: BasicHeader = BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"),
    ): Transform {
        val response = client().makeRequest("GET", "${IndexManagementPlugin.TRANSFORM_BASE_URI}/$transformId", null, header)
        assertEquals("Unable to get transform $transformId", RestStatus.OK, response.restStatus())

        val parser = createParser(XContentType.JSON.xContent(), response.entity.content)
        ensureExpectedToken(Token.START_OBJECT, parser.nextToken(), parser)

        lateinit var id: String
        var primaryTerm = SequenceNumbers.UNASSIGNED_PRIMARY_TERM
        var seqNo = SequenceNumbers.UNASSIGNED_SEQ_NO
        lateinit var transform: Transform

        while (parser.nextToken() != Token.END_OBJECT) {
            parser.nextToken()

            when (parser.currentName()) {
                _ID -> id = parser.text()
                _SEQ_NO -> seqNo = parser.longValue()
                _PRIMARY_TERM -> primaryTerm = parser.longValue()
                Transform.TRANSFORM_TYPE -> transform = Transform.parse(parser, id, seqNo, primaryTerm)
            }
        }
        return transform
    }

    protected fun getTransformMetadata(
        metadataId: String,
        header: BasicHeader = BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"),
    ): TransformMetadata {
        val response = adminClient().makeRequest("GET", "$INDEX_MANAGEMENT_INDEX/_doc/$metadataId", null, header)
        assertEquals("Unable to get transform metadata $metadataId", RestStatus.OK, response.restStatus())

        val parser = createParser(XContentType.JSON.xContent(), response.entity.content)
        ensureExpectedToken(Token.START_OBJECT, parser.nextToken(), parser)

        lateinit var id: String
        var primaryTerm = SequenceNumbers.UNASSIGNED_PRIMARY_TERM
        var seqNo = SequenceNumbers.UNASSIGNED_SEQ_NO
        lateinit var metadata: TransformMetadata

        while (parser.nextToken() != Token.END_OBJECT) {
            parser.nextToken()

            when (parser.currentName()) {
                _ID -> id = parser.text()
                _SEQ_NO -> seqNo = parser.longValue()
                _PRIMARY_TERM -> primaryTerm = parser.longValue()
                TransformMetadata.TRANSFORM_METADATA_TYPE -> metadata = TransformMetadata.parse(parser, id, seqNo, primaryTerm)
            }
        }

        return metadata
    }

    protected fun deleteSnapshot(repository: String, snapshotName: String) {
        val response = client().makeRequest("DELETE", "_snapshot/$repository/$snapshotName")
        assertEquals("Unable to delete snapshot", RestStatus.OK, response.restStatus())
    }

    @Suppress("UNCHECKED_CAST")
    protected fun assertSnapshotExists(
        repository: String,
        snapshot: String,
    ) = require(getSnapshotsList(repository).any { element -> (element as Map<String, String>)["id"]!!.startsWith(snapshot) }) { "No snapshot found with id: $snapshot" }

    @Suppress("UNCHECKED_CAST")
    protected fun assertSnapshotFinishedWithSuccess(
        repository: String,
        snapshot: String,
    ) = require(getSnapshotsList(repository).any { element -> (element as Map<String, String>)["id"]!!.startsWith(snapshot) && "SUCCESS" == element["status"] }) { "Snapshot didn't finish with success." }

    /**
     * Compares responses returned by APIs such as those defined in [RetryFailedManagedIndexAction] and [RestAddPolicyAction]
     *
     * Example response with no failures:
     * {
     *   "failures": false,
     *   "updated_indices": 3
     *   "failed_indices": []
     * }
     *
     * Example response with failures:
     * {
     *   "failures": true,
     *   "failed_indices": [
     *     {
     *       "index_name": "indexName",
     *       "index_uuid": "s1PvTKzaThWoeA43eTPYxQ"
     *       "reason": "Reason for failure"
     *     }
     *   ]
     * }
     */
    @Suppress("UNCHECKED_CAST")
    protected fun assertAffectedIndicesResponseIsEqual(expected: Map<String, Any>, actual: Map<String, Any>) {
        for (entry in actual) {
            val key = entry.key
            val value = entry.value

            when {
                key == FAILURES && value is Boolean -> assertEquals(expected[key] as Boolean, value)
                key == UPDATED_INDICES && value is Int -> assertEquals(expected[key] as Int, value)
                key == FAILED_INDICES && value is List<*> -> {
                    val actualArray = (value as List<Map<String, String>>).toTypedArray()
                    actualArray.sortWith(compareBy { it["index_name"] })
                    val expectedArray = (expected[key] as List<Map<String, String>>).toTypedArray()
                    expectedArray.sortWith(compareBy { it["index_name"] })

                    assertArrayEquals(expectedArray, actualArray)
                }
                else -> fail("Unknown field: [$key] or incorrect type for value: [$value]")
            }
        }
    }

    /**
     * indexPredicates is a list of pairs where first is index name and second is a list of pairs
     * where first is key property and second is predicate function to assert on
     *
     * @param indexPredicates list of index to list of predicates to assert on
     * @param response explain response to use for assertions
     * @param strict if true all fields must be handled in assertions
     */
    @Suppress("UNCHECKED_CAST")
    protected fun assertPredicatesOnMetaData(
        indexPredicates: List<Pair<String, List<Pair<String, (Any?) -> Boolean>>>>,
        response: Map<String, Any?>,
        strict: Boolean = true,
    ) {
        indexPredicates.forEach { (index, predicates) ->
            assertTrue("The index: $index was not found in the response: $response", response.containsKey(index))
            val indexResponse = response[index] as Map<String, String?>
            if (strict) {
                val predicatesSet = predicates.map { it.first }.toSet()
                assertEquals("The fields do not match, response=($indexResponse) predicates=$predicatesSet", predicatesSet, indexResponse.keys.toSet())
            }
            predicates.forEach { (fieldName, predicate) ->
                assertTrue("The key: $fieldName was not found in the response: $indexResponse", indexResponse.containsKey(fieldName))
                assertTrue("Failed predicate assertion for $fieldName response=($indexResponse) predicates=$predicates", predicate(indexResponse[fieldName]))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected fun assertRetryInfoEquals(expectedRetryInfo: PolicyRetryInfoMetaData, actualRetryInfoMetaDataMap: Any?): Boolean {
        actualRetryInfoMetaDataMap as Map<String, Any>
        assertEquals(expectedRetryInfo.failed, actualRetryInfoMetaDataMap[PolicyRetryInfoMetaData.FAILED] as Boolean)
        assertEquals(expectedRetryInfo.consumedRetries, actualRetryInfoMetaDataMap[PolicyRetryInfoMetaData.CONSUMED_RETRIES] as Int)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    protected fun assertStateEquals(expectedState: StateMetaData, actualStateMap: Any?): Boolean {
        actualStateMap as Map<String, Any>
        assertEquals(expectedState.name, actualStateMap[ManagedIndexMetaData.NAME] as String)
        assertTrue((actualStateMap[ManagedIndexMetaData.START_TIME] as Long) < expectedState.startTime)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    protected fun assertActionEquals(expectedAction: ActionMetaData, actualActionMap: Any?): Boolean {
        actualActionMap as Map<String, Any>
        assertEquals(expectedAction.name, actualActionMap[ManagedIndexMetaData.NAME] as String)
        assertEquals(expectedAction.failed, actualActionMap[ActionMetaData.FAILED] as Boolean)
        val expectedStartTime = expectedAction.startTime
        if (expectedStartTime != null) {
            assertTrue((actualActionMap[ManagedIndexMetaData.START_TIME] as Long) < expectedStartTime)
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    protected fun assertStepEquals(expectedStep: StepMetaData, actualStepMap: Any?): Boolean {
        actualStepMap as Map<String, Any>
        assertEquals(expectedStep.name, actualStepMap[ManagedIndexMetaData.NAME] as String)
        assertEquals(expectedStep.stepStatus.toString(), actualStepMap[StepMetaData.STEP_STATUS])
        val expectedStartTime = expectedStep.startTime
        assertTrue((actualStepMap[ManagedIndexMetaData.START_TIME] as Long) < expectedStartTime)
        return true
    }

    @Suppress("UNCHECKED_CAST")
    protected fun assertPredicatesOnISMTemplatesMap(
        templatePredicates: List<Pair<String, List<Pair<String, (Any?) -> Boolean>>>>, // response map name: predicate
        response: Map<String, Any?>,
    ) {
        val templates = response["ism_templates"] as ArrayList<Map<String, Any?>>

        templatePredicates.forEachIndexed { ind, (_, predicates) ->
            val template = templates[ind]
            predicates.forEach { (fieldName, predicate) ->
                assertTrue("The key: $fieldName was not found in the response: $template", template.containsKey(fieldName))
                assertTrue("Failed predicate assertion for $fieldName in response=($template) predicate=$predicate", predicate(template[fieldName]))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected fun assertISMTemplateEquals(expected: ISMTemplate, actualISMTemplateMap: Any?): Boolean {
        actualISMTemplateMap as Map<String, Any>
        assertEquals(expected.indexPatterns, actualISMTemplateMap[ISMTemplate.INDEX_PATTERN])
        assertEquals(expected.priority, actualISMTemplateMap[ISMTemplate.PRIORITY])
        return true
    }

    protected fun assertISMTemplateEquals(expected: ISMTemplate, actual: ISMTemplate?): Boolean {
        assertNotNull(actual)
        if (actual != null) {
            assertEquals(expected.indexPatterns, actual.indexPatterns)
            assertEquals(expected.priority, actual.priority)
        }
        return true
    }

    protected fun createV1Template(templateName: String, indexPatterns: String, policyID: String, order: Int = 0) {
        val response =
            client().makeRequest(
                "PUT", "_template/$templateName",
                StringEntity(
                    "{\n" +
                        "  \"index_patterns\": [\"$indexPatterns\"],\n" +
                        "  \"settings\": {\n" +
                        "    \"opendistro.index_state_management.policy_id\": \"$policyID\"\n" +
                        "  }, \n" +
                        "  \"order\": $order\n" +
                        "}",
                    ContentType.APPLICATION_JSON,
                ),
            )
        assertEquals("Request failed", RestStatus.OK, response.restStatus())
    }

    protected fun createV1Template2(templateName: String, indexPatterns: String, order: Int = 0) {
        val response =
            client().makeRequest(
                "PUT", "_template/$templateName",
                StringEntity(
                    "{\n" +
                        "  \"index_patterns\": [\"$indexPatterns\"],\n" +
                        "  \"order\": $order\n" +
                        "}",
                    ContentType.APPLICATION_JSON,
                ),
            )
        assertEquals("Request failed", RestStatus.OK, response.restStatus())
    }

    protected fun deleteV1Template(templateName: String) {
        val response = client().makeRequest("DELETE", "_template/$templateName")
        assertEquals("Request failed", RestStatus.OK, response.restStatus())
    }

    protected fun createV2Template(templateName: String, indexPatterns: String, policyID: String) {
        val response =
            client().makeRequest(
                "PUT", "_index_template/$templateName",
                StringEntity(
                    "{\n" +
                        "  \"index_patterns\": [\"$indexPatterns\"],\n" +
                        "  \"template\": {\n" +
                        "    \"settings\": {\n" +
                        "      \"opendistro.index_state_management.policy_id\": \"$policyID\"\n" +
                        "    }\n" +
                        "  }\n" +
                        "}",
                    ContentType.APPLICATION_JSON,
                ),
            )
        assertEquals("Request failed", RestStatus.OK, response.restStatus())
    }

    protected fun deleteV2Template(templateName: String) {
        val response = client().makeRequest("DELETE", "_index_template/$templateName")
        assertEquals("Request failed", RestStatus.OK, response.restStatus())
    }

    fun catIndexTemplates(): List<Any> {
        val response = client().makeRequest("GET", "_cat/templates?format=json")
        logger.info("response: $response")

        assertEquals("cat template request failed", RestStatus.OK, response.restStatus())

        try {
            return jsonXContent
                .createParser(
                    NamedXContentRegistry.EMPTY,
                    DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                    response.entity.content,
                )
                .use { parser -> parser.list() }
        } catch (e: IOException) {
            throw OpenSearchParseException("Failed to parse content to list", e)
        }
    }

    override fun xContentRegistry(): NamedXContentRegistry = NamedXContentRegistry(
        listOf(
            ClusterModule.getNamedXWriteables(),
            SearchModule(Settings.EMPTY, emptyList()).namedXContents,
        ).flatten(),
    )
}
