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

package org.apache.ignite.internal.table.distributed;

import static org.apache.ignite.internal.catalog.CatalogService.DEFAULT_SCHEMA_NAME;
import static org.apache.ignite.internal.catalog.commands.CatalogUtils.pkIndexName;
import static org.apache.ignite.internal.table.TableTestUtils.INDEX_NAME;
import static org.apache.ignite.internal.table.TableTestUtils.TABLE_NAME;
import static org.apache.ignite.internal.table.TableTestUtils.createSimpleHashIndex;
import static org.apache.ignite.internal.table.TableTestUtils.createSimpleTable;
import static org.apache.ignite.internal.table.TableTestUtils.dropIndex;
import static org.apache.ignite.internal.table.TableTestUtils.getIndexIdStrict;
import static org.apache.ignite.internal.table.TableTestUtils.getTableIdStrict;
import static org.apache.ignite.internal.table.TableTestUtils.makeIndexAvailable;
import static org.apache.ignite.internal.table.TableTestUtils.removeIndex;
import static org.apache.ignite.internal.table.TableTestUtils.startBuildingIndex;
import static org.apache.ignite.internal.table.distributed.TableUtils.indexIdsAtRwTxBeginTs;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.apache.ignite.internal.tx.TransactionIds.transactionId;
import static org.apache.ignite.internal.util.IgniteUtils.closeAll;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.function.Consumer;
import org.apache.ignite.internal.catalog.CatalogManager;
import org.apache.ignite.internal.catalog.CatalogService;
import org.apache.ignite.internal.catalog.CatalogTestUtils;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridClockImpl;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.testframework.IgniteAbstractTest;
import org.junit.jupiter.api.Test;

/** For {@link TableUtils} testing. */
public class TableUtilsTest extends IgniteAbstractTest {
    private final HybridClock clock = new HybridClockImpl();

    @Test
    void testIndexIdsAtRwTxBeginTs() throws Exception {
        withCatalogManager(catalogManager -> {
            createSimpleTable(catalogManager, TABLE_NAME);

            String indexName0 = INDEX_NAME + 0;
            String indexName1 = INDEX_NAME + 1;
            String indexName2 = INDEX_NAME + 2;
            String indexName3 = INDEX_NAME + 3;
            String indexName4 = INDEX_NAME + 4;

            for (String indexName : List.of(indexName0, indexName1, indexName2, indexName3, indexName4)) {
                createSimpleHashIndex(catalogManager, TABLE_NAME, indexName);
            }

            int indexId0 = indexId(catalogManager, indexName0);
            int indexId1 = indexId(catalogManager, indexName1);
            int indexId2 = indexId(catalogManager, indexName2);
            int indexId3 = indexId(catalogManager, indexName3);
            int indexId4 = indexId(catalogManager, indexName4);

            for (String indexName : List.of(indexName1, indexName2, indexName3, indexName4)) {
                startBuildingIndex(catalogManager, indexId(catalogManager, indexName));
            }

            for (String indexName : List.of(indexName2, indexName3, indexName4)) {
                makeIndexAvailable(catalogManager, indexId(catalogManager, indexName));
            }

            for (String indexName : List.of(indexName3, indexName4)) {
                dropIndex(catalogManager, DEFAULT_SCHEMA_NAME, indexName);
            }

            removeIndex(catalogManager, indexId4);

            CatalogManager spy = spy(catalogManager);

            HybridTimestamp beginTs = clock.now();

            int tableId = getTableIdStrict(catalogManager, TABLE_NAME, clock.nowLong());

            assertThat(
                    indexIdsAtRwTxBeginTs(spy, transactionId(beginTs, 1), tableId),
                    contains(
                            indexId(catalogManager, pkIndexName(TABLE_NAME)),
                            indexId0,
                            indexId1,
                            indexId2,
                            indexId3
                    )
            );

            verify(spy).activeCatalogVersion(eq(beginTs.longValue()));
            verify(spy).indexes(eq(catalogManager.activeCatalogVersion(beginTs.longValue())), eq(tableId));
        });
    }

    private void withCatalogManager(Consumer<CatalogManager> consumer) throws Exception {
        CatalogManager catalogManager = CatalogTestUtils.createTestCatalogManager("test-node", clock);

        assertThat(catalogManager.start(), willCompleteSuccessfully());

        try {
            consumer.accept(catalogManager);
        } finally {
            closeAll(catalogManager::beforeNodeStop, catalogManager::stop);
        }
    }

    private int indexId(CatalogService catalogService, String indexName) {
        return getIndexIdStrict(catalogService, indexName, clock.nowLong());
    }
}
