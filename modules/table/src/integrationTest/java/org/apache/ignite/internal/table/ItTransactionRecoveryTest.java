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

package org.apache.ignite.internal.table;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.ignite.internal.SessionUtils.executeUpdate;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.waitForCondition;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureExceptionMatcher.willThrow;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.apache.ignite.internal.util.ExceptionUtils.extractCodeFrom;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.apache.ignite.InitParametersBuilder;
import org.apache.ignite.internal.ClusterPerTestIntegrationTest;
import org.apache.ignite.internal.app.IgniteImpl;
import org.apache.ignite.internal.lang.IgniteBiTuple;
import org.apache.ignite.internal.network.ClusterService;
import org.apache.ignite.internal.network.DefaultMessagingService;
import org.apache.ignite.internal.network.NetworkMessage;
import org.apache.ignite.internal.placementdriver.ReplicaMeta;
import org.apache.ignite.internal.placementdriver.message.PlacementDriverMessagesFactory;
import org.apache.ignite.internal.placementdriver.message.StopLeaseProlongationMessage;
import org.apache.ignite.internal.replicator.ReplicationGroupId;
import org.apache.ignite.internal.replicator.TablePartitionId;
import org.apache.ignite.internal.replicator.message.ErrorTimestampAwareReplicaResponse;
import org.apache.ignite.internal.replicator.message.TimestampAwareReplicaResponse;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.table.distributed.replication.request.ReadWriteSingleRowReplicaRequest;
import org.apache.ignite.internal.testframework.IgniteTestUtils;
import org.apache.ignite.internal.testframework.flow.TestFlowUtils;
import org.apache.ignite.internal.tx.HybridTimestampTracker;
import org.apache.ignite.internal.tx.InternalTransaction;
import org.apache.ignite.internal.tx.MismatchingTransactionOutcomeException;
import org.apache.ignite.internal.tx.TxMeta;
import org.apache.ignite.internal.tx.TxState;
import org.apache.ignite.internal.tx.TxStateMeta;
import org.apache.ignite.internal.tx.configuration.TransactionConfiguration;
import org.apache.ignite.internal.tx.message.TxFinishReplicaRequest;
import org.apache.ignite.internal.tx.message.TxRecoveryMessage;
import org.apache.ignite.internal.tx.message.TxStateCommitPartitionRequest;
import org.apache.ignite.internal.util.ExceptionUtils;
import org.apache.ignite.lang.ErrorGroups.Transactions;
import org.apache.ignite.network.ClusterNode;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Tuple;
import org.apache.ignite.tx.Transaction;
import org.apache.ignite.tx.TransactionException;
import org.apache.ignite.tx.TransactionOptions;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Abandoned transactions integration tests.
 */
public class ItTransactionRecoveryTest extends ClusterPerTestIntegrationTest {
    private static final PlacementDriverMessagesFactory PLACEMENT_DRIVER_MESSAGES_FACTORY = new PlacementDriverMessagesFactory();

    /** Table name. */
    private static final String TABLE_NAME = "test_table";

    private static final int PART_ID = 0;

    @BeforeEach
    @Override
    public void setup(TestInfo testInfo) throws Exception {
        super.setup(testInfo);

        String zoneSql = "create zone test_zone with partitions=1, replicas=3";
        String sql = "create table " + TABLE_NAME + " (key int primary key, val varchar(20)) with primary_zone='TEST_ZONE'";

        cluster.doInSession(0, session -> {
            executeUpdate(zoneSql, session);
            executeUpdate(sql, session);
        });
    }

    @Override
    protected void customizeInitParameters(InitParametersBuilder builder) {
        super.customizeInitParameters(builder);

        builder.clusterConfiguration("{\n"
                + "  \"transaction\": {\n"
                + "  \"abandonedCheckTs\": 600000\n"
                + "  }\n"
                + "}\n");
    }

    @Test
    public void testMultipleRecoveryRequestsIssued() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator is chosen [node={}].", txCrdNode.name());

        Transaction oldRwTx = node(0).transactions().begin();

        UUID orphanTxId = startTransactionAndStopNode(txCrdNode);

        CompletableFuture<UUID> recoveryTxMsgCaptureFut = new CompletableFuture<>();
        AtomicInteger msgCount = new AtomicInteger();

        commitPartNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TxRecoveryMessage) {
                var recoveryTxMsg = (TxRecoveryMessage) msg;

                recoveryTxMsgCaptureFut.complete(recoveryTxMsg.txId());

                // Drop only the first recovery to emulate a lost message.
                // Another one should be issued eventually.
                return msgCount.incrementAndGet() == 1;
            }

            return false;
        });

        runConflictingTransaction(node(0), oldRwTx);
        runConflictingTransaction(node(0), node(0).transactions().begin());

        assertThat(recoveryTxMsgCaptureFut, willCompleteSuccessfully());

        assertEquals(orphanTxId, recoveryTxMsgCaptureFut.join());
        assertEquals(1, msgCount.get());

        node(0).clusterConfiguration().getConfiguration(TransactionConfiguration.KEY).change(transactionChange ->
                transactionChange.changeAbandonedCheckTs(1));

        assertTrue(waitForCondition(() -> {
            runConflictingTransaction(node(0), node(0).transactions().begin());

            return msgCount.get() > 1;
        }, 10_000));
    }

    @Test
    public void testAbandonedTxIsAborted() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator is chosen [node={}].", txCrdNode.name());

        UUID orphanTxId = startTransactionAndStopNode(txCrdNode);

        CompletableFuture<UUID> recoveryTxMsgCaptureFut = new CompletableFuture<>();
        AtomicInteger msgCount = new AtomicInteger();

        commitPartNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TxRecoveryMessage) {
                var recoveryTxMsg = (TxRecoveryMessage) msg;

                recoveryTxMsgCaptureFut.complete(recoveryTxMsg.txId());

                msgCount.incrementAndGet();
            }

            return false;
        });

        runConflictingTransaction(node(0), node(0).transactions().begin());

        assertThat(recoveryTxMsgCaptureFut, willCompleteSuccessfully());

        assertEquals(orphanTxId, recoveryTxMsgCaptureFut.join());
        assertEquals(1, msgCount.get());

        assertTrue(waitForCondition(() -> txStoredState(commitPartNode, orphanTxId) == TxState.ABORTED, 10_000));
    }

    @Test
    public void testWriteIntentRecoverNoCoordinator() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator is chosen [node={}].", txCrdNode.name());

        UUID orphanTxId = startTransactionAndStopNode(txCrdNode);

        AtomicInteger msgCount = new AtomicInteger();

        IgniteImpl roCoordNode = node(0);

        log.info("RO Transaction coordinator is chosen [node={}].", roCoordNode.name());

        commitPartNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TxStateCommitPartitionRequest) {
                msgCount.incrementAndGet();

                assertEquals(TxState.ABANDONED, txVolatileState(commitPartNode, orphanTxId));
            }

            return false;
        });

        Transaction recoveryTxReadOnly = roCoordNode.transactions().begin(new TransactionOptions().readOnly(true));

        runReadOnlyTransaction(roCoordNode, recoveryTxReadOnly);

        assertEquals(1, msgCount.get());

        assertTrue(waitForCondition(() -> txStoredState(commitPartNode, orphanTxId) == TxState.ABORTED, 10_000));
    }

    /**
     * Coordinator is alive, no recovery expected.
     */
    @Test
    public void testWriteIntentNoRecovery() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator is chosen [node={}].", txCrdNode.name());

        Transaction rwTransaction = createRwTransaction(txCrdNode);

        AtomicInteger msgCount = new AtomicInteger();

        IgniteImpl roCoordNode = node(0);

        log.info("RO Transaction coordinator is chosen [node={}].", roCoordNode.name());

        commitPartNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TxRecoveryMessage) {
                msgCount.incrementAndGet();
            }

            return false;
        });

        Transaction recoveryTxReadOnly = roCoordNode.transactions().begin(new TransactionOptions().readOnly(true));

        runReadOnlyTransaction(roCoordNode, recoveryTxReadOnly);

        assertEquals(0, msgCount.get());

        rwTransaction.commit();

        UUID rwId = ((InternalTransaction) rwTransaction).id();

        assertTrue(waitForCondition(() -> txStoredState(commitPartNode, rwId) == TxState.COMMITTED, 10_000));
    }

    @Test
    public void testWriteIntentRecoveryAndLockConflict() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator is chosen [node={}].", txCrdNode.name());

        UUID orphanTxId = startTransactionAndStopNode(txCrdNode);

        AtomicInteger stateMsgCount = new AtomicInteger();
        AtomicInteger recoveryMsgCount = new AtomicInteger();

        IgniteImpl roCoordNode = node(0);

        log.info("RO Transaction coordinator is chosen [node={}].", roCoordNode.name());

        CompletableFuture<UUID> txMsgCaptureFut = new CompletableFuture<>();

        commitPartNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TxStateCommitPartitionRequest) {
                stateMsgCount.incrementAndGet();

                assertEquals(TxState.ABANDONED, txVolatileState(commitPartNode, orphanTxId));

                txMsgCaptureFut.complete(((TxStateCommitPartitionRequest) msg).txId());
            }

            if (msg instanceof TxRecoveryMessage) {
                recoveryMsgCount.incrementAndGet();
            }

            return false;
        });

        Transaction recoveryTxReadOnly = roCoordNode.transactions().begin(new TransactionOptions().readOnly(true));

        RecordView view = roCoordNode.tables().table(TABLE_NAME).recordView();

        view.getAsync(recoveryTxReadOnly, Tuple.create().set("key", 42));

        assertThat(txMsgCaptureFut, willCompleteSuccessfully());

        runConflictingTransaction(commitPartNode, commitPartNode.transactions().begin());

        assertEquals(1, stateMsgCount.get());

        assertEquals(0, recoveryMsgCount.get());

        assertTrue(waitForCondition(() -> txStoredState(commitPartNode, orphanTxId) == TxState.ABORTED, 10_000));
    }

    /**
     * Coordinator sends a commit message and dies. The message eventually reaches the commit partition and gets executed.
     * The expected outcome of the transaction is COMMIT.
     */
    @Test
    public void testSendCommitAndDie() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator is chosen [node={}].", txCrdNode.name());

        InternalTransaction orphanTx = (InternalTransaction) createRwTransaction(txCrdNode);

        CompletableFuture<TxFinishReplicaRequest> finishRequestCaptureFut = new CompletableFuture<>();
        AtomicReference<String> targetName = new AtomicReference<>();

        // Intercept the commit message, prevent it form being sent. We will kill this node anyway.
        txCrdNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TxFinishReplicaRequest) {
                var finishTxMsg = (TxFinishReplicaRequest) msg;

                finishRequestCaptureFut.complete(finishTxMsg);
                targetName.set(nodeName);

                return true;
            }

            return false;
        });

        // Initiate commit.
        orphanTx.commitAsync();

        assertThat(finishRequestCaptureFut, willCompleteSuccessfully());

        // Stop old coordinator.
        String txCrdNodeId = txCrdNode.id();

        txCrdNode.stop();

        assertTrue(waitForCondition(
                () -> node(0).clusterNodes().stream().filter(n -> txCrdNodeId.equals(n.id())).count() == 0,
                10_000)
        );

        // The state on the commit partition is still PENDING.
        assertEquals(TxState.PENDING, txVolatileState(commitPartNode, orphanTx.id()));

        // Continue the COMMIT message flow.
        CompletableFuture<NetworkMessage> finishRequest =
                messaging(commitPartNode).invoke(targetName.get(), finishRequestCaptureFut.join(), 3000);

        assertThat(finishRequest, willCompleteSuccessfully());

        // The conflicting transaction should see an already committed TX.
        runRwTransactionNoError(node(0), node(0).transactions().begin());

        assertTrue(waitForCondition(() -> txStoredState(commitPartNode, orphanTx.id()) == TxState.COMMITTED, 10_000));
    }

    /**
     * Coordinator sends a commit message and dies. Another tx initiates recovery and aborts this transaction.
     * The commit message eventually reaches the commit partition and gets executed but the outcome is ABORTED.
     */
    @Test
    public void testCommitAndDieRecoveryFirst() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator is chosen [node={}].", txCrdNode.name());

        InternalTransaction orphanTx = (InternalTransaction) createRwTransaction(txCrdNode);

        CompletableFuture<TxFinishReplicaRequest> finishRequestCaptureFut = new CompletableFuture<>();
        AtomicReference<String> targetName = new AtomicReference<>();

        // Intercept the commit message, prevent it form being sent. We will kill this node anyway.
        txCrdNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TxFinishReplicaRequest) {
                var finishTxMsg = (TxFinishReplicaRequest) msg;

                finishRequestCaptureFut.complete(finishTxMsg);
                targetName.set(nodeName);

                return true;
            }

            return false;
        });

        // Initiate commit.
        orphanTx.commitAsync();

        assertThat(finishRequestCaptureFut, willCompleteSuccessfully());

        // Stop old coordinator.
        String txCrdNodeId = txCrdNode.id();

        txCrdNode.stop();

        assertTrue(waitForCondition(
                () -> node(0).clusterNodes().stream().filter(n -> txCrdNodeId.equals(n.id())).count() == 0,
                10_000)
        );

        // The state on the commit partition is still PENDING.
        assertEquals(TxState.PENDING, txVolatileState(commitPartNode, orphanTx.id()));

        IgniteImpl newTxCoord = node(0);

        runRwTransactionNoError(newTxCoord, newTxCoord.transactions().begin());

        assertTrue(waitForCondition(() -> txStoredState(commitPartNode, orphanTx.id()) == TxState.ABORTED, 10_000));

        CompletableFuture<NetworkMessage> commitRequest =
                messaging(commitPartNode).invoke(targetName.get(), finishRequestCaptureFut.join(), 3000);

        assertThat(commitRequest, willCompleteSuccessfully());

        NetworkMessage response = commitRequest.join();

        assertInstanceOf(ErrorTimestampAwareReplicaResponse.class, response);

        ErrorTimestampAwareReplicaResponse errorResponse = (ErrorTimestampAwareReplicaResponse) response;

        assertInstanceOf(MismatchingTransactionOutcomeException.class, ExceptionUtils.unwrapCause(errorResponse.throwable()));

        assertEquals(TxState.ABORTED, txStoredState(commitPartNode, orphanTx.id()));
    }

    @Test
    public void testRecoveryIsTriggeredOnce() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator is chosen [node={}].", txCrdNode.name());

        UUID orphanTxId = startTransactionAndStopNode(txCrdNode);

        log.info("Orphan tx [id={}]", orphanTxId);

        CompletableFuture<UUID> recoveryTxMsgCaptureFut = new CompletableFuture<>();
        AtomicInteger msgCount = new AtomicInteger();

        commitPartNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TxRecoveryMessage) {
                var recoveryTxMsg = (TxRecoveryMessage) msg;

                recoveryTxMsgCaptureFut.complete(recoveryTxMsg.txId());

                msgCount.incrementAndGet();
            }

            return false;
        });

        IgniteImpl newCoordNode = node(0);

        log.info("New transaction coordinator is chosen [node={}].", newCoordNode.name());

        // Run RW transaction.
        Transaction rwTx1 = commitPartNode.transactions().begin();

        UUID rwTx1Id = ((InternalTransaction) rwTx1).id();

        log.info("First concurrent tx [id={}]", rwTx1Id);

        runConflictingTransaction(commitPartNode, rwTx1);

        Transaction rwTx2 = newCoordNode.transactions().begin();

        UUID rwTx2Id = ((InternalTransaction) rwTx2).id();

        log.info("Second concurrent tx [id={}]", rwTx2Id);

        runRwTransactionNoError(newCoordNode, rwTx2);

        assertThat(recoveryTxMsgCaptureFut, willCompleteSuccessfully());

        assertEquals(orphanTxId, recoveryTxMsgCaptureFut.join());
        assertEquals(1, msgCount.get());

        rwTx2.commit();

        assertTrue(waitForCondition(() -> txStoredState(commitPartNode, orphanTxId) == TxState.ABORTED, 10_000));
        assertTrue(waitForCondition(() -> txStoredState(commitPartNode, rwTx1Id) == TxState.ABORTED, 10_000));

        Transaction rwTx3 = newCoordNode.transactions().begin();

        log.info("Start RW tx {}", ((InternalTransaction) rwTx3).id());

        runRwTransactionNoError(newCoordNode, rwTx3);

        rwTx3.commit();

        assertTrue(waitForCondition(() -> txStoredState(commitPartNode, ((InternalTransaction) rwTx3).id()) == TxState.COMMITTED, 10_000));
    }

    @Test
    public void testFinishAlreadyFinishedTx() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator is chosen [node={}].", txCrdNode.name());

        Transaction rwTx1 = createRwTransaction(txCrdNode);

        rwTx1.commit();

        UUID rwTx1Id = ((InternalTransaction) rwTx1).id();

        assertTrue(waitForCondition(() -> txStoredState(commitPartNode, rwTx1Id) == TxState.COMMITTED, 10_000));

        IgniteImpl txCrdNode2 = node(0);

        CompletableFuture<Void> finish2 = txCrdNode2.txManager().finish(
                new HybridTimestampTracker(),
                ((InternalTransaction) rwTx1).commitPartition(),
                false,
                Map.of(((InternalTransaction) rwTx1).commitPartition(), new IgniteBiTuple<>(txCrdNode2.node(), 0L)),
                rwTx1Id
        );

        assertThat(finish2, willThrow(MismatchingTransactionOutcomeException.class));
    }

    @Test
    public void testPrimaryFailureRightAfterCommitMsg() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator node is determined [node={}].", txCrdNode.name());

        Transaction rwTx1 = createRwTransaction(txCrdNode);

        CompletableFuture<?> commitMsgSentFut = new CompletableFuture<>();
        CompletableFuture<?> cancelLeaseFuture = new CompletableFuture<>();

        txCrdNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TxFinishReplicaRequest) {
                boolean isFirst = !commitMsgSentFut.isDone();

                if (isFirst) {
                    commitMsgSentFut.complete(null);

                    return true;
                } else {
                    cancelLeaseFuture.join();

                    return false;
                }
            }

            return false;
        });

        CompletableFuture<Void> commitFut = rwTx1.commitAsync();

        assertThat(commitMsgSentFut, willCompleteSuccessfully());

        cancelLease(commitPartNode, tblReplicationGrp);

        waitAndGetLeaseholder(txCrdNode, tblReplicationGrp);

        cancelLeaseFuture.complete(null);

        assertThat(commitFut, willCompleteSuccessfully());

        RecordView<Tuple> view = txCrdNode.tables().table(TABLE_NAME).recordView();

        var rec = view.get(null, Tuple.create().set("key", 42));

        assertNotNull(rec);
        assertEquals((Integer) 42, rec.value("key"));
        assertEquals("val1", rec.value("val"));
    }

    @Test
    public void testPrimaryFailureWhileInflightInProgress() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator node is determined [node={}].", txCrdNode.name());

        Transaction rwTx1 = createRwTransaction(txCrdNode);

        txCrdNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof ReadWriteSingleRowReplicaRequest) {
                return true;
            }

            return false;
        });

        assertThrows(TransactionException.class, () -> {
            RecordView<Tuple> view = txCrdNode.tables().table(TABLE_NAME).recordView();
            view.upsert(rwTx1, Tuple.create().set("key", 1).set("val", "val1"));
        });

        CompletableFuture<Void> commitFut = rwTx1.commitAsync();

        commitPartNode.stop();

        assertThat(commitFut, willCompleteSuccessfully());
    }

    @Test
    public void testPrimaryFailureWhileInflightInProgressAfterFirstResponse() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), 0);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator node is determined [node={}].", txCrdNode.name());

        CompletableFuture<?> firstResponseSent = new CompletableFuture<>();

        commitPartNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TimestampAwareReplicaResponse) {
                TimestampAwareReplicaResponse response = (TimestampAwareReplicaResponse) msg;

                if (response.result() == null) {
                    firstResponseSent.complete(null);
                }

                // This means this is the second response that finishes an in-flight future.
                if (response.result() instanceof UUID) {
                    return true;
                }
            }

            return false;
        });

        Transaction rwTx1 = createRwTransaction(txCrdNode);

        CompletableFuture<Void> commitFut = rwTx1.commitAsync();

        assertThat(firstResponseSent, willCompleteSuccessfully());

        cancelLease(commitPartNode, tblReplicationGrp);

        assertThat(commitFut, willThrow(MismatchingTransactionOutcomeException.class, 30, SECONDS));

        RecordView<Tuple> view = txCrdNode.tables().table(TABLE_NAME).recordView();

        var rec = view.get(null, Tuple.create().set("key", 42));

        assertNull(rec);
    }

    @Test
    public void testTsRecoveryForCursor() throws Exception {
        TableImpl tbl = (TableImpl) node(0).tables().table(TABLE_NAME);

        RecordView view1 = node(0).tables().table(TABLE_NAME).recordView();

        for (int i = 0; i < 10; i++) {
            view1.upsert(null, Tuple.create().set("key", i).set("val", "preload"));
        }

        var tblReplicationGrp = new TablePartitionId(tbl.tableId(), PART_ID);

        String leaseholder = waitAndGetLeaseholder(node(0), tblReplicationGrp);

        IgniteImpl commitPartNode = commitPartitionPrimaryNode(leaseholder);

        log.info("Transaction commit partition is determined [node={}].", commitPartNode.name());

        IgniteImpl txCrdNode = nonPrimaryNode(leaseholder);

        log.info("Transaction coordinator is chosen [node={}].", txCrdNode.name());

        startTransactionWithCursorAndStopNode(txCrdNode);

        IgniteImpl newCoordNode = node(0);

        log.info("New transaction coordinator is chosen [node={}].", newCoordNode.name());

        CompletableFuture<Void> txMsgCaptureFut = new CompletableFuture<>();

        commitPartNode.dropMessages((nodeName, msg) -> {
            if (msg instanceof TxRecoveryMessage) {
                txMsgCaptureFut.complete(null);
            }

            return false;
        });

        Transaction tx = newCoordNode.transactions().begin();

        RecordView view = newCoordNode.tables().table(TABLE_NAME).recordView();

        var opFut = view.upsertAsync(tx, Tuple.create().set("key", 42).set("val", "new"));

        try {
            opFut.get();
        } catch (Exception ex) {
            log.info("Expected conflict that have to start recovery: " + ex.getMessage());
        }

        assertThat(txMsgCaptureFut, willCompleteSuccessfully());
    }

    private UUID startTransactionWithCursorAndStopNode(IgniteImpl txCrdNode) throws Exception {
        InternalTransaction rwTx = (InternalTransaction) txCrdNode.transactions().begin();

        scanSingleEntryAndLeaveCursorOpen((TableViewInternal) txCrdNode.tables().table(TABLE_NAME), rwTx);

        String txCrdNodeId = txCrdNode.id();

        txCrdNode.stop();

        assertTrue(waitForCondition(
                () -> node(0).clusterNodes().stream().filter(n -> txCrdNodeId.equals(n.id())).count() == 0,
                10_000)
        );

        return rwTx.id();
    }

    /**
     * Starts a scan procedure for a specific transaction and reads only the first line from the cursor.
     *
     * @param tbl Scanned table.
     * @param tx Transaction.
     * @throws Exception If failed.
     */
    private void scanSingleEntryAndLeaveCursorOpen(TableViewInternal tbl, InternalTransaction tx)
            throws Exception {
        Publisher<BinaryRow> publisher;
        if (tx.isReadOnly()) {
            String primaryId = waitAndGetLeaseholder(node(0), new TablePartitionId(tbl.tableId(), PART_ID));

            ClusterNode primaryNode = node(0).clusterNodes().stream().filter(node -> node.id().equals(primaryId)).findAny().get();

            publisher = tbl.internalTable().scan(PART_ID, tx.id(), tx.readTimestamp(), primaryNode, tx.coordinatorId());
        } else {
            publisher = tbl.internalTable().scan(PART_ID, tx);
        }

        List<BinaryRow> scannedRows = new ArrayList<>();
        CompletableFuture<Void> scanned = new CompletableFuture<>();

        Subscription subscription = TestFlowUtils.subscribeToPublisher(scannedRows, publisher, scanned);

        subscription.request(1);

        assertTrue(waitForCondition(() -> scannedRows.size() == 1, 10_000));

        assertFalse(scanned.isDone());
    }

    private DefaultMessagingService messaging(IgniteImpl node) {
        ClusterService coordinatorService = IgniteTestUtils.getFieldValue(node, IgniteImpl.class, "clusterSvc");

        return (DefaultMessagingService) coordinatorService.messagingService();
    }

    private @Nullable TxState txVolatileState(IgniteImpl node, UUID txId) {
        TxStateMeta txMeta = node.txManager().stateMeta(txId);

        return txMeta == null ? null : txMeta.txState();
    }

    private @Nullable TxState txStoredState(IgniteImpl node, UUID txId) {
        TxMeta txMeta = txStoredMeta(node, txId);

        return txMeta == null ? null : txMeta.txState();
    }

    private @Nullable TxMeta txStoredMeta(IgniteImpl node, UUID txId) {
        InternalTable internalTable = ((TableViewInternal) node.tables().table(TABLE_NAME)).internalTable();

        return internalTable.txStateStorage().getTxStateStorage(0).get(txId);
    }

    /**
     * Runs a transaction that was expectedly finished with the lock conflict exception.
     *
     * @param node Transaction coordinator node.
     * @param rwTx A transaction to create a lock conflict with an abandoned one.
     */
    private void runConflictingTransaction(IgniteImpl node, Transaction rwTx) {
        RecordView view = node.tables().table(TABLE_NAME).recordView();

        try {
            view.upsert(rwTx, Tuple.create().set("key", 42).set("val", "val2"));

            fail("Lock conflict have to be detected.");
        } catch (Exception e) {
            assertEquals(Transactions.ACQUIRE_LOCK_ERR, extractCodeFrom(e));

            log.info("Expected lock conflict.", e);
        }
    }

    private void runRwTransactionNoError(IgniteImpl node, Transaction rwTx) {
        RecordView view = node.tables().table(TABLE_NAME).recordView();

        try {
            view.upsert(rwTx, Tuple.create().set("key", 42).set("val", "val2"));
        } catch (Exception e) {
            assertEquals(Transactions.ACQUIRE_LOCK_ERR, extractCodeFrom(e));

            log.info("Expected lock conflict.", e);
        }
    }

    /**
     * Runs a RO transaction to trigger recovery on write intent resolutioin.
     *
     * @param node Transaction coordinator node.
     * @param roTx A transaction to resolve write intents from the abandoned TX.
     */
    private void runReadOnlyTransaction(IgniteImpl node, Transaction roTx) {
        RecordView view = node.tables().table(TABLE_NAME).recordView();

        try {
            view.get(roTx, Tuple.create().set("key", 42));
        } catch (Exception e) {
            assertEquals(Transactions.ACQUIRE_LOCK_ERR, extractCodeFrom(e));

            log.info("Expected lock conflict.", e);
        }
    }

    /**
     * Starts the transaction, takes a lock, and stops the transaction coordinator. The stopped node leaves the transaction in the pending
     * state.
     *
     * @param node Transaction coordinator node.
     * @return Transaction id.
     * @throws InterruptedException If interrupted.
     */
    private UUID startTransactionAndStopNode(IgniteImpl node) throws InterruptedException {
        Transaction rwTx1 = createRwTransaction(node);

        String txCrdNodeId = node.id();

        node.stop();

        assertTrue(waitForCondition(
                () -> node(0).clusterNodes().stream().filter(n -> txCrdNodeId.equals(n.id())).count() == 0,
                10_000)
        );
        return ((InternalTransaction) rwTx1).id();
    }

    /**
     * Creates RW the transaction.
     *
     * @param node Transaction coordinator node.
     * @return Transaction id.
     */
    private Transaction createRwTransaction(IgniteImpl node) {
        RecordView<Tuple> view = node.tables().table(TABLE_NAME).recordView();

        Transaction rwTx1 = node.transactions().begin();

        view.upsert(rwTx1, Tuple.create().set("key", 42).set("val", "val1"));

        return rwTx1;
    }

    private IgniteImpl findNode(int startRange, int endRange, Predicate<IgniteImpl> filter) {
        return IntStream.range(startRange, endRange)
                .mapToObj(this::node)
                .filter(filter::test)
                .findFirst()
                .get();
    }

    private IgniteImpl commitPartitionPrimaryNode(String leaseholder) {
        return findNode(0, initialNodes(), n -> leaseholder.equals(n.name()));
    }

    private IgniteImpl nonPrimaryNode(String leaseholder) {
        return findNode(1, initialNodes(), n -> !leaseholder.equals(n.name()));
    }

    private ReplicaMeta waitAndGetPrimaryReplica(IgniteImpl node, ReplicationGroupId tblReplicationGrp) {
        CompletableFuture<ReplicaMeta> primaryReplicaFut = node.placementDriver().awaitPrimaryReplica(
                tblReplicationGrp,
                node.clock().now(),
                10,
                SECONDS
        );

        assertThat(primaryReplicaFut, willCompleteSuccessfully());

        return primaryReplicaFut.join();
    }

    private String waitAndGetLeaseholder(IgniteImpl node, ReplicationGroupId tblReplicationGrp) {
        return waitAndGetPrimaryReplica(node, tblReplicationGrp).getLeaseholder();
    }

    private void cancelLease(IgniteImpl leaseholder, ReplicationGroupId groupId) {
        StopLeaseProlongationMessage msg = PLACEMENT_DRIVER_MESSAGES_FACTORY
                .stopLeaseProlongationMessage()
                .groupId(groupId)
                .build();

        // Just sent it to all nodes to not determine the exact placement driver active actor.
        runningNodes().forEach(node -> leaseholder.sendFakeMessage(node.name(), msg));
    }
}
