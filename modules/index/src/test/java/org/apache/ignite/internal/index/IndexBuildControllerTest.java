/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.index;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.ignite.internal.catalog.CatalogService.DEFAULT_SCHEMA_NAME;
import static org.apache.ignite.internal.catalog.CatalogTestUtils.createTestCatalogManager;
import static org.apache.ignite.internal.catalog.commands.CatalogUtils.pkIndexName;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.COLUMN_NAME;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.INDEX_NAME;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.LOCAL_NODE;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.NODE_ID;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.NODE_NAME;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.TABLE_NAME;
import static org.apache.ignite.internal.index.TestIndexManagementUtils.createTable;
import static org.apache.ignite.internal.table.TableTestUtils.createHashIndex;
import static org.apache.ignite.internal.table.TableTestUtils.getIndexIdStrict;
import static org.apache.ignite.internal.table.TableTestUtils.getIndexStrict;
import static org.apache.ignite.internal.table.TableTestUtils.getTableIdStrict;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.catalog.CatalogManager;
import org.apache.ignite.internal.catalog.commands.MakeIndexAvailableCommand;
import org.apache.ignite.internal.catalog.commands.StartBuildingIndexCommand;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridClockImpl;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.network.ClusterService;
import org.apache.ignite.internal.placementdriver.ReplicaMeta;
import org.apache.ignite.internal.placementdriver.leases.Lease;
import org.apache.ignite.internal.replicator.TablePartitionId;
import org.apache.ignite.internal.storage.MvPartitionStorage;
import org.apache.ignite.internal.storage.engine.MvTableStorage;
import org.apache.ignite.internal.storage.index.IndexStorage;
import org.apache.ignite.internal.table.TableTestUtils;
import org.apache.ignite.internal.testframework.BaseIgniteAbstractTest;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.network.TopologyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** For {@link IndexBuildController} testing. */
public class IndexBuildControllerTest extends BaseIgniteAbstractTest {
    private static final int PARTITION_ID = 10;

    private IndexBuilder indexBuilder;

    private CatalogManager catalogManager;

    private IndexBuildController indexBuildController;

    private final TestPlacementDriver placementDriver = new TestPlacementDriver();

    private final HybridClock clock = new HybridClockImpl();

    @BeforeEach
    void setUp() {
        indexBuilder = mock(IndexBuilder.class);

        IndexManager indexManager = mock(IndexManager.class, invocation -> {
            MvTableStorage mvTableStorage = mock(MvTableStorage.class);
            MvPartitionStorage mvPartitionStorage = mock(MvPartitionStorage.class);
            IndexStorage indexStorage = mock(IndexStorage.class);

            when(mvTableStorage.getMvPartition(anyInt())).thenReturn(mvPartitionStorage);
            when(mvTableStorage.getIndex(anyInt(), anyInt())).thenReturn(indexStorage);

            return completedFuture(mvTableStorage);
        });

        ClusterService clusterService = mock(ClusterService.class, invocation -> mock(TopologyService.class, invocation1 -> LOCAL_NODE));

        catalogManager = createTestCatalogManager(NODE_NAME, clock);
        assertThat(catalogManager.start(), willCompleteSuccessfully());

        indexBuildController = new IndexBuildController(indexBuilder, indexManager, catalogManager, clusterService, placementDriver, clock);

        createTable(catalogManager, TABLE_NAME, COLUMN_NAME);
    }

    @AfterEach
    void tearDown() throws Exception {
        IgniteUtils.closeAll(
                catalogManager == null ? null : catalogManager::stop,
                indexBuildController == null ? null : indexBuildController::close
        );
    }

    @Test
    void testStartBuildIndexesOnIndexCreate() {
        setPrimaryReplicaWhichExpiresInOneSecond(PARTITION_ID, NODE_NAME, NODE_ID, clock.now());

        clearInvocations(indexBuilder);

        createIndex(INDEX_NAME);

        verify(indexBuilder, never()).scheduleBuildIndex(
                eq(tableId()),
                eq(PARTITION_ID),
                eq(indexId(INDEX_NAME)),
                any(),
                any(),
                eq(LOCAL_NODE),
                anyLong(),
                eq(indexCreationCatalogVersion(INDEX_NAME))
        );
    }

    @Test
    void testStartBuildIndexesOnIndexBuilding() {
        setPrimaryReplicaWhichExpiresInOneSecond(PARTITION_ID, NODE_NAME, NODE_ID, clock.now());

        clearInvocations(indexBuilder);

        createIndex(INDEX_NAME);

        startBuildingIndex(indexId(INDEX_NAME));

        verify(indexBuilder).scheduleBuildIndex(
                eq(tableId()),
                eq(PARTITION_ID),
                eq(indexId(INDEX_NAME)),
                any(),
                any(),
                eq(LOCAL_NODE),
                anyLong(),
                eq(indexCreationCatalogVersion(INDEX_NAME))
        );
    }

    @Test
    void testStartBuildIndexesOnPrimaryReplicaElected() {
        createIndex(INDEX_NAME);

        startBuildingIndex(indexId(INDEX_NAME));

        setPrimaryReplicaWhichExpiresInOneSecond(PARTITION_ID, NODE_NAME, NODE_ID, clock.now());

        verify(indexBuilder).scheduleBuildIndex(
                eq(tableId()),
                eq(PARTITION_ID),
                eq(indexId(INDEX_NAME)),
                any(),
                any(),
                eq(LOCAL_NODE),
                anyLong(),
                eq(indexCreationCatalogVersion(INDEX_NAME))
        );
    }

    @Test
    void testNotStartBuildPkIndexesOnPrimaryReplicaElected() {
        setPrimaryReplicaWhichExpiresInOneSecond(PARTITION_ID, NODE_NAME, NODE_ID, clock.now());

        verify(indexBuilder, never()).scheduleBuildIndex(
                eq(tableId()),
                eq(PARTITION_ID),
                eq(indexId(pkIndexName(TABLE_NAME))),
                any(),
                any(),
                eq(LOCAL_NODE),
                anyLong(),
                eq(indexCreationCatalogVersion(pkIndexName(TABLE_NAME)))
        );
    }

    @Test
    void testNotStartBuildPkIndexesForNewTable() {
        setPrimaryReplicaWhichExpiresInOneSecond(PARTITION_ID, NODE_NAME, NODE_ID, clock.now());

        String tableName = TABLE_NAME + "_new";

        createTable(catalogManager, tableName, COLUMN_NAME);

        verify(indexBuilder, never()).scheduleBuildIndex(
                eq(tableId()),
                eq(PARTITION_ID),
                eq(indexId(pkIndexName(tableName))),
                any(),
                any(),
                eq(LOCAL_NODE),
                anyLong(),
                eq(indexCreationCatalogVersion(pkIndexName(tableName)))
        );
    }

    @Test
    void testStopBuildIndexesOnIndexDrop() {
        createIndex(INDEX_NAME);

        int indexId = indexId(INDEX_NAME);

        dropIndex(INDEX_NAME);

        verify(indexBuilder).stopBuildingIndexes(indexId);
    }

    @Test
    void testStopBuildIndexesOnChangePrimaryReplica() {
        setPrimaryReplicaWhichExpiresInOneSecond(PARTITION_ID, NODE_NAME, NODE_ID, clock.now());
        setPrimaryReplicaWhichExpiresInOneSecond(PARTITION_ID, NODE_NAME + "_other", NODE_ID + "_other", clock.now());

        verify(indexBuilder).stopBuildingIndexes(tableId(), PARTITION_ID);
    }

    @Test
    void testStartBuildIndexesOnPrimaryReplicaElectedOnlyForBuildingIndexes() {
        createIndex(INDEX_NAME + 0);
        createIndex(INDEX_NAME + 1);

        int indexId0 = indexId(INDEX_NAME + 0);

        startBuildingIndex(indexId0);
        makeIndexAvailable(indexId0);

        setPrimaryReplicaWhichExpiresInOneSecond(PARTITION_ID, NODE_NAME, NODE_ID, clock.now());

        verify(indexBuilder, never()).scheduleBuildIndex(
                eq(tableId()),
                eq(PARTITION_ID),
                anyInt(),
                any(),
                any(),
                eq(LOCAL_NODE),
                anyLong(),
                anyInt()
        );
    }

    private void createIndex(String indexName) {
        createHashIndex(catalogManager, DEFAULT_SCHEMA_NAME, TABLE_NAME, indexName, List.of(COLUMN_NAME), false);
    }

    private void startBuildingIndex(int indexId) {
        assertThat(catalogManager.execute(StartBuildingIndexCommand.builder().indexId(indexId).build()), willCompleteSuccessfully());
    }

    private void makeIndexAvailable(int indexId) {
        assertThat(catalogManager.execute(MakeIndexAvailableCommand.builder().indexId(indexId).build()), willCompleteSuccessfully());
    }

    private void dropIndex(String indexName) {
        TableTestUtils.dropIndex(catalogManager, DEFAULT_SCHEMA_NAME, indexName);
    }

    private void setPrimaryReplicaWhichExpiresInOneSecond(
            int partitionId,
            String leaseholder,
            String leaseholderId,
            HybridTimestamp startTime
    ) {
        CompletableFuture<ReplicaMeta> replicaMetaFuture = completedFuture(replicaMetaForOneSecond(leaseholder, leaseholderId, startTime));

        assertThat(placementDriver.setPrimaryReplicaMeta(0, replicaId(partitionId), replicaMetaFuture), willCompleteSuccessfully());
    }

    private int tableId() {
        return getTableIdStrict(catalogManager, TABLE_NAME, clock.nowLong());
    }

    private int indexId(String indexName) {
        return getIndexIdStrict(catalogManager, indexName, clock.nowLong());
    }

    private TablePartitionId replicaId(int partitionId) {
        return new TablePartitionId(tableId(), partitionId);
    }

    private ReplicaMeta replicaMetaForOneSecond(String leaseholder, String leaseholderId, HybridTimestamp startTime) {
        return new Lease(
                leaseholder,
                leaseholderId,
                startTime,
                startTime.addPhysicalTime(1_000),
                new TablePartitionId(tableId(), PARTITION_ID)
        );
    }

    private int indexCreationCatalogVersion(String indexName) {
        return getIndexStrict(catalogManager, indexName, clock.nowLong()).txWaitCatalogVersion();
    }
}
