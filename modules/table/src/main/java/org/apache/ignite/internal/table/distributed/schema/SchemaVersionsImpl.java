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

package org.apache.ignite.internal.table.distributed.schema;

import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.catalog.CatalogService;
import org.apache.ignite.internal.catalog.descriptors.CatalogTableDescriptor;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridTimestamp;

/**
 * Default implementation of {@link SchemaVersions}.
 */
public class SchemaVersionsImpl implements SchemaVersions {
    private final SchemaSyncService schemaSyncService;

    private final CatalogService catalogService;

    private final HybridClock clock;

    /**
     * Creates a new instance.
     *
     * @param schemaSyncService Service to use for schema synchronization.
     * @param catalogService Catalog access.
     * @param clock Clock used by the node.
     */
    public SchemaVersionsImpl(SchemaSyncService schemaSyncService, CatalogService catalogService, HybridClock clock) {
        this.schemaSyncService = schemaSyncService;
        this.catalogService = catalogService;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<Integer> schemaVersionAt(HybridTimestamp timestamp, int tableId) {
        return tableDescriptor(tableId, timestamp)
                .thenApply(CatalogTableDescriptor::tableVersion);
    }

    private CompletableFuture<CatalogTableDescriptor> tableDescriptor(int tableId, HybridTimestamp timestamp) {
        return schemaSyncService.waitForMetadataCompleteness(timestamp)
                .thenApply(unused -> {
                    CatalogTableDescriptor table = catalogService.table(tableId, timestamp.longValue());

                    assert table != null : "No table in the catalog after schema sync, table " + tableId + ", ts " + timestamp;

                    return table;
                });
    }

    @Override
    public CompletableFuture<Integer> schemaVersionAtNow(int tableId) {
        return schemaVersionAt(clock.now(), tableId);
    }
}
