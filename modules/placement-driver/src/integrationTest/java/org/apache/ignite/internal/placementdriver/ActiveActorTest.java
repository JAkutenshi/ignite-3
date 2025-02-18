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

package org.apache.ignite.internal.placementdriver;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.waitForCondition;
import static org.apache.ignite.internal.util.CompletableFutures.trueCompletedFuture;
import static org.apache.ignite.internal.util.IgniteUtils.closeAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ignite.internal.cluster.management.topology.api.LogicalTopologyService;
import org.apache.ignite.internal.hlc.HybridClockImpl;
import org.apache.ignite.internal.metastorage.Entry;
import org.apache.ignite.internal.metastorage.MetaStorageManager;
import org.apache.ignite.internal.metastorage.dsl.Operation;
import org.apache.ignite.internal.network.ClusterService;
import org.apache.ignite.internal.raft.Loza;
import org.apache.ignite.internal.raft.Peer;
import org.apache.ignite.internal.raft.PeersAndLearners;
import org.apache.ignite.internal.raft.client.AbstractTopologyAwareGroupServiceTest;
import org.apache.ignite.internal.raft.client.TopologyAwareRaftGroupServiceFactory;
import org.apache.ignite.raft.jraft.rpc.impl.RaftGroupEventsClientListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Placement driver active actor test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ActiveActorTest extends AbstractTopologyAwareGroupServiceTest {
    private final Map<String, PlacementDriverManager> placementDriverManagers = new HashMap<>();

    @Mock
    MetaStorageManager msm;

    @BeforeEach
    public void setUp() {
        when(msm.recoveryFinishedFuture()).thenReturn(completedFuture(0L));
        when(msm.invoke(any(), any(Operation.class), any(Operation.class))).thenReturn(trueCompletedFuture());
        when(msm.getLocally(any(), anyLong())).then(invocation -> emptyMetastoreEntry());
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        super.tearDown();

        List<AutoCloseable> closeables = placementDriverManagers.values().stream().map(p -> (AutoCloseable) p::stop).collect(toList());

        closeAll(closeables);

        placementDriverManagers.clear();
    }

    @Override
    protected void afterNodeStart(
            String nodeName,
            ClusterService clusterService,
            Path dataPath,
            PeersAndLearners peersAndLearners,
            RaftGroupEventsClientListener eventsClientListener,
            LogicalTopologyService logicalTopologyService
    ) {
        Set<String> placementDriverNodesNames = peersAndLearners.peers().stream().map(Peer::consistentId).collect(toSet());

        var raftManager = new Loza(clusterService, raftConfiguration, dataPath, new HybridClockImpl(), eventsClientListener);

        var raftGroupServiceFactory = new TopologyAwareRaftGroupServiceFactory(
                clusterService,
                logicalTopologyService,
                Loza.FACTORY,
                eventsClientListener
        );

        PlacementDriverManager placementDriverManager = new PlacementDriverManager(
                nodeName,
                msm,
                GROUP_ID,
                clusterService,
                () -> completedFuture(placementDriverNodesNames),
                logicalTopologyService,
                raftManager,
                raftGroupServiceFactory,
                new HybridClockImpl()
        );

        placementDriverManager.start();

        placementDriverManagers.put(nodeName, placementDriverManager);
    }

    @Override
    protected void afterNodeStop(String nodeName) throws Exception {
        PlacementDriverManager placementDriverManager = placementDriverManagers.remove(nodeName);

        placementDriverManager.stop();
    }

    private boolean checkSingleActiveActor(String leaderName) {
        for (Map.Entry<String, PlacementDriverManager> e : placementDriverManagers.entrySet()) {
            if (e.getValue().isActiveActor() != e.getKey().equals(leaderName)) {
                return false;
            }
        }

        return true;
    }

    @Override
    protected void afterClusterInit(String leaderName) throws InterruptedException {
        assertTrue(waitForCondition(() -> checkSingleActiveActor(leaderName), WAIT_TIMEOUT_MILLIS));
    }

    @Override
    protected void afterLeaderChange(String leaderName) throws InterruptedException {
        assertTrue(waitForCondition(() -> checkSingleActiveActor(leaderName), WAIT_TIMEOUT_MILLIS));
    }

    private static Entry emptyMetastoreEntry() {
        Entry entry = mock(Entry.class);

        when(entry.empty()).thenReturn(true);

        return entry;
    }
}
