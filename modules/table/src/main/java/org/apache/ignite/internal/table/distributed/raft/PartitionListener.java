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

package org.apache.ignite.internal.table.distributed.raft;

import static java.util.Objects.requireNonNull;
import static org.apache.ignite.internal.hlc.HybridTimestamp.CLOCK_SKEW;
import static org.apache.ignite.internal.lang.IgniteStringFormatter.format;
import static org.apache.ignite.internal.table.distributed.TableUtils.indexIdsAtRwTxBeginTs;
import static org.apache.ignite.internal.tx.TxState.ABORTED;
import static org.apache.ignite.internal.tx.TxState.COMMITTED;
import static org.apache.ignite.internal.tx.TxState.PENDING;
import static org.apache.ignite.internal.util.CollectionUtils.last;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.ignite.internal.catalog.Catalog;
import org.apache.ignite.internal.catalog.CatalogService;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.lang.IgniteInternalException;
import org.apache.ignite.internal.lang.SafeTimeReorderException;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.raft.Command;
import org.apache.ignite.internal.raft.ReadCommand;
import org.apache.ignite.internal.raft.WriteCommand;
import org.apache.ignite.internal.raft.service.BeforeApplyHandler;
import org.apache.ignite.internal.raft.service.CommandClosure;
import org.apache.ignite.internal.raft.service.CommittedConfiguration;
import org.apache.ignite.internal.raft.service.RaftGroupListener;
import org.apache.ignite.internal.replicator.command.SafeTimePropagatingCommand;
import org.apache.ignite.internal.replicator.command.SafeTimeSyncCommand;
import org.apache.ignite.internal.storage.BinaryRowAndRowId;
import org.apache.ignite.internal.storage.MvPartitionStorage;
import org.apache.ignite.internal.storage.MvPartitionStorage.Locker;
import org.apache.ignite.internal.storage.RowId;
import org.apache.ignite.internal.table.distributed.StorageUpdateHandler;
import org.apache.ignite.internal.table.distributed.command.BuildIndexCommand;
import org.apache.ignite.internal.table.distributed.command.FinishTxCommand;
import org.apache.ignite.internal.table.distributed.command.UpdateAllCommand;
import org.apache.ignite.internal.table.distributed.command.UpdateCommand;
import org.apache.ignite.internal.table.distributed.command.WriteIntentSwitchCommand;
import org.apache.ignite.internal.tx.TransactionResult;
import org.apache.ignite.internal.tx.TxManager;
import org.apache.ignite.internal.tx.TxMeta;
import org.apache.ignite.internal.tx.TxState;
import org.apache.ignite.internal.tx.TxStateMeta;
import org.apache.ignite.internal.tx.storage.state.TxStateStorage;
import org.apache.ignite.internal.util.PendingComparableValuesTracker;
import org.apache.ignite.internal.util.TrackerClosedException;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * Partition command handler.
 */
public class PartitionListener implements RaftGroupListener, BeforeApplyHandler {
    /** Logger. */
    private static final IgniteLogger LOG = Loggers.forClass(PartitionListener.class);

    /** Transaction manager. */
    private final TxManager txManager;

    /** Partition storage with access to MV data of a partition. */
    private final PartitionDataStorage storage;

    /** Handler that processes storage updates. */
    private final StorageUpdateHandler storageUpdateHandler;

    /** Storage of transaction metadata. */
    private final TxStateStorage txStateStorage;

    /** Safe time tracker. */
    private final PendingComparableValuesTracker<HybridTimestamp, Void> safeTime;

    /** Storage index tracker. */
    private final PendingComparableValuesTracker<Long, Void> storageIndexTracker;

    /** Is used in order to detect and retry safe time reordering within onBeforeApply. */
    private volatile long maxObservableSafeTime = -1;

    /** Is used in order to assert safe time reordering within onWrite. */
    private long maxObservableSafeTimeVerifier = -1;

    private final CatalogService catalogService;

    /**
     * The constructor.
     *
     * @param txManager Transaction manager.
     * @param partitionDataStorage The storage.
     * @param safeTime Safe time tracker.
     * @param storageIndexTracker Storage index tracker.
     * @param catalogService Catalog service.
     */
    public PartitionListener(
            TxManager txManager,
            PartitionDataStorage partitionDataStorage,
            StorageUpdateHandler storageUpdateHandler,
            TxStateStorage txStateStorage,
            PendingComparableValuesTracker<HybridTimestamp, Void> safeTime,
            PendingComparableValuesTracker<Long, Void> storageIndexTracker,
            CatalogService catalogService
    ) {
        this.txManager = txManager;
        this.storage = partitionDataStorage;
        this.storageUpdateHandler = storageUpdateHandler;
        this.txStateStorage = txStateStorage;
        this.safeTime = safeTime;
        this.storageIndexTracker = storageIndexTracker;
        this.catalogService = catalogService;
    }

    @Override
    public void onRead(Iterator<CommandClosure<ReadCommand>> iterator) {
        iterator.forEachRemaining((CommandClosure<? extends ReadCommand> clo) -> {
            Command command = clo.command();

            assert false : "No read commands expected, [cmd=" + command + ']';
        });
    }

    @Override
    public void onWrite(Iterator<CommandClosure<WriteCommand>> iterator) {
        iterator.forEachRemaining((CommandClosure<? extends WriteCommand> clo) -> {
            Command command = clo.command();

            if (command instanceof SafeTimePropagatingCommand) {
                SafeTimePropagatingCommand cmd = (SafeTimePropagatingCommand) command;
                long proposedSafeTime = cmd.safeTime().longValue();

                // Because of clock.tick it's guaranteed that two different commands will have different safe timestamps.
                // maxObservableSafeTime may match proposedSafeTime only if it is the command that was previously validated and then retried
                // by raft client because of either TimeoutException or inner raft server recoverable exception.
                assert proposedSafeTime >= maxObservableSafeTimeVerifier : "Safe time reordering detected [current="
                        + maxObservableSafeTimeVerifier + ", proposed=" + proposedSafeTime + "]";

                maxObservableSafeTimeVerifier = proposedSafeTime;
            }

            long commandIndex = clo.index();
            long commandTerm = clo.term();

            // We choose the minimum applied index, since we choose it (the minimum one) on local recovery so as not to lose the data for
            // one of the storages.
            long storagesAppliedIndex = Math.min(storage.lastAppliedIndex(), txStateStorage.lastAppliedIndex());

            assert commandIndex > storagesAppliedIndex :
                    "Write command must have an index greater than that of storages [commandIndex=" + commandIndex
                            + ", mvAppliedIndex=" + storage.lastAppliedIndex()
                            + ", txStateAppliedIndex=" + txStateStorage.lastAppliedIndex() + "]";

            // NB: Make sure that ANY command we accept here updates lastAppliedIndex+term info in one of the underlying
            // storages!
            // Otherwise, a gap between lastAppliedIndex from the point of view of JRaft and our storage might appear.
            // If a leader has such a gap, and does doSnapshot(), it will subsequently truncate its log too aggressively
            // in comparison with 'snapshot' state stored in our storages; and if we install a snapshot from our storages
            // to a follower at this point, for a subsequent AppendEntries the leader will not be able to get prevLogTerm
            // (because it's already truncated in the leader's log), so it will have to install a snapshot again, and then
            // repeat same thing over and over again.

            storage.acquirePartitionSnapshotsReadLock();

            Serializable result = null;

            try {
                if (command instanceof UpdateCommand) {
                    handleUpdateCommand((UpdateCommand) command, commandIndex, commandTerm);
                } else if (command instanceof UpdateAllCommand) {
                    handleUpdateAllCommand((UpdateAllCommand) command, commandIndex, commandTerm);
                } else if (command instanceof FinishTxCommand) {
                    result = handleFinishTxCommand((FinishTxCommand) command, commandIndex, commandTerm);
                } else if (command instanceof WriteIntentSwitchCommand) {
                    handleWriteIntentSwitchCommand((WriteIntentSwitchCommand) command, commandIndex, commandTerm);
                } else if (command instanceof SafeTimeSyncCommand) {
                    handleSafeTimeSyncCommand((SafeTimeSyncCommand) command, commandIndex, commandTerm);
                } else if (command instanceof BuildIndexCommand) {
                    handleBuildIndexCommand((BuildIndexCommand) command, commandIndex, commandTerm);
                } else {
                    assert false : "Command was not found [cmd=" + command + ']';
                }

                clo.result(result);
            } catch (IgniteInternalException e) {
                clo.result(e);
            } catch (CompletionException e) {
                clo.result(e.getCause());
            } catch (Throwable t) {
                LOG.error(
                        "Unknown error while processing command [commandIndex={}, commandTerm={}, command={}]",
                        t,
                        clo.index(), clo.index(), command
                );

                throw t;
            } finally {
                storage.releasePartitionSnapshotsReadLock();
            }

            if (command instanceof SafeTimePropagatingCommand) {
                SafeTimePropagatingCommand safeTimePropagatingCommand = (SafeTimePropagatingCommand) command;

                assert safeTimePropagatingCommand.safeTime() != null;

                synchronized (safeTime) {
                    updateTrackerIgnoringTrackerClosedException(safeTime, safeTimePropagatingCommand.safeTime());
                }
            }

            updateTrackerIgnoringTrackerClosedException(storageIndexTracker, commandIndex);
        });
    }

    /**
     * Handler for the {@link UpdateCommand}.
     *
     * @param cmd Command.
     * @param commandIndex Index of the RAFT command.
     * @param commandTerm Term of the RAFT command.
     */
    private void handleUpdateCommand(UpdateCommand cmd, long commandIndex, long commandTerm) {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= storage.lastAppliedIndex()) {
            return;
        }

        UUID txId = cmd.txId();

        // TODO: https://issues.apache.org/jira/browse/IGNITE-20124 Proper storage/raft index handling is required.
        synchronized (safeTime) {
            if (cmd.safeTime().compareTo(safeTime.current()) > 0) {
                storageUpdateHandler.handleUpdate(
                        txId,
                        cmd.rowUuid(),
                        cmd.tablePartitionId().asTablePartitionId(),
                        cmd.rowToUpdate(),
                        !cmd.full(),
                        () -> storage.lastApplied(commandIndex, commandTerm),
                        cmd.full() ? cmd.safeTime() : null,
                        cmd.lastCommitTimestamp(),
                        indexIdsAtRwTxBeginTs(catalogService, txId, storage.tableId())
                );
            }

            updateTrackerIgnoringTrackerClosedException(safeTime, cmd.safeTime());
        }

        replicaTouch(txId, cmd.txCoordinatorId(), cmd.full() ? cmd.safeTime() : null, cmd.full());
    }

    /**
     * Handler for the {@link UpdateAllCommand}.
     *
     * @param cmd Command.
     * @param commandIndex Index of the RAFT command.
     * @param commandTerm Term of the RAFT command.
     */
    private void handleUpdateAllCommand(UpdateAllCommand cmd, long commandIndex, long commandTerm) {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= storage.lastAppliedIndex()) {
            return;
        }

        UUID txId = cmd.txId();

        // TODO: https://issues.apache.org/jira/browse/IGNITE-20124 Proper storage/raft index handling is required.
        synchronized (safeTime) {
            if (cmd.safeTime().compareTo(safeTime.current()) > 0) {
                storageUpdateHandler.handleUpdateAll(
                        txId,
                        cmd.rowsToUpdate(),
                        cmd.tablePartitionId().asTablePartitionId(),
                        !cmd.full(),
                        () -> storage.lastApplied(commandIndex, commandTerm),
                        cmd.full() ? cmd.safeTime() : null,
                        indexIdsAtRwTxBeginTs(catalogService, txId, storage.tableId())
                );

                updateTrackerIgnoringTrackerClosedException(safeTime, cmd.safeTime());
            }
        }

        replicaTouch(txId, cmd.txCoordinatorId(), cmd.full() ? cmd.safeTime() : null, cmd.full());
    }

    /**
     * Handler for the {@link FinishTxCommand}.
     *
     * @param cmd Command.
     * @param commandIndex Index of the RAFT command.
     * @param commandTerm Term of the RAFT command.
     * @return The actually stored transaction state {@link TransactionResult}.
     * @throws IgniteInternalException if an exception occurred during a transaction state change.
     */
    private TransactionResult handleFinishTxCommand(FinishTxCommand cmd, long commandIndex, long commandTerm)
            throws IgniteInternalException {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= txStateStorage.lastAppliedIndex()) {
            return null;
        }

        UUID txId = cmd.txId();

        TxState stateToSet = cmd.commit() ? COMMITTED : ABORTED;

        TxMeta txMetaToSet = new TxMeta(
                stateToSet,
                cmd.commitTimestamp()
        );

        TxMeta txMetaBeforeCas = txStateStorage.get(txId);

        boolean txStateChangeRes = txStateStorage.compareAndSet(
                txId,
                null,
                txMetaToSet,
                commandIndex,
                commandTerm
        );

        markFinished(txId, cmd.commit(), cmd.commitTimestamp());

        LOG.debug("Finish the transaction txId = {}, state = {}, txStateChangeRes = {}", txId, txMetaToSet, txStateChangeRes);

        if (!txStateChangeRes) {
            onTxStateStorageCasFail(txId, txMetaBeforeCas, txMetaToSet);
        }

        return new TransactionResult(stateToSet, cmd.commitTimestamp());
    }


    /**
     * Handler for the {@link WriteIntentSwitchCommand}.
     *
     * @param cmd Command.
     * @param commandIndex Index of the RAFT command.
     * @param commandTerm Term of the RAFT command.
     */
    private void handleWriteIntentSwitchCommand(WriteIntentSwitchCommand cmd, long commandIndex, long commandTerm) {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= storage.lastAppliedIndex()) {
            return;
        }

        UUID txId = cmd.txId();

        markFinished(txId, cmd.commit(), cmd.commitTimestamp());

        storageUpdateHandler.switchWriteIntents(
                txId,
                cmd.commit(),
                cmd.commitTimestamp(),
                () -> storage.lastApplied(commandIndex, commandTerm),
                indexIdsAtRwTxBeginTs(catalogService, txId, storage.tableId())
        );
    }

    /**
     * Handler for the {@link SafeTimeSyncCommand}.
     *
     * @param cmd Command.
     * @param commandIndex RAFT index of the command.
     * @param commandTerm RAFT term of the command.
     */
    private void handleSafeTimeSyncCommand(SafeTimeSyncCommand cmd, long commandIndex, long commandTerm) {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= storage.lastAppliedIndex()) {
            return;
        }

        // We MUST bump information about last updated index+term.
        // See a comment in #onWrite() for explanation.
        storage.runConsistently(locker -> {
            storage.lastApplied(commandIndex, commandTerm);

            return null;
        });
    }

    @Override
    public void onConfigurationCommitted(CommittedConfiguration config) {
        // Skips the update because the storage has already recorded it.
        if (config.index() <= storage.lastAppliedIndex()) {
            return;
        }

        // Do the update under lock to make sure no snapshot is started concurrently with this update.
        // Note that we do not need to protect from a concurrent command execution by this listener because
        // configuration is committed in the same thread in which commands are applied.
        storage.acquirePartitionSnapshotsReadLock();

        try {
            storage.runConsistently(locker -> {
                storage.committedGroupConfiguration(
                        new RaftGroupConfiguration(config.peers(), config.learners(), config.oldPeers(), config.oldLearners())
                );
                storage.lastApplied(config.index(), config.term());
                updateTrackerIgnoringTrackerClosedException(storageIndexTracker, config.index());

                return null;
            });
        } finally {
            storage.releasePartitionSnapshotsReadLock();
        }
    }

    @Override
    public void onSnapshotSave(Path path, Consumer<Throwable> doneClo) {
        // The max index here is required for local recovery and a possible scenario
        // of false node failure when we actually have all required data. This might happen because we use the minimal index
        // among storages on a node restart.
        // Let's consider a more detailed example:
        //      1) We don't propagate the maximal lastAppliedIndex among storages, and onSnapshotSave finishes, it leads to the raft log
        //         truncation until the maximal lastAppliedIndex.
        //      2) Unexpected cluster restart happens.
        //      3) Local recovery of a node is started, where we request data from the minimal lastAppliedIndex among storages, because
        //         some data for some node might not have been flushed before unexpected cluster restart.
        //      4) When we try to restore data starting from the minimal lastAppliedIndex, we come to the situation
        //         that a raft node doesn't have such data, because the truncation until the maximal lastAppliedIndex from 1) has happened.
        //      5) Node cannot finish local recovery.
        long maxLastAppliedIndex = Math.max(storage.lastAppliedIndex(), txStateStorage.lastAppliedIndex());
        long maxLastAppliedTerm = Math.max(storage.lastAppliedTerm(), txStateStorage.lastAppliedTerm());

        storage.runConsistently(locker -> {
            storage.lastApplied(maxLastAppliedIndex, maxLastAppliedTerm);

            return null;
        });

        txStateStorage.lastApplied(maxLastAppliedIndex, maxLastAppliedTerm);
        updateTrackerIgnoringTrackerClosedException(storageIndexTracker, maxLastAppliedIndex);

        CompletableFuture.allOf(storage.flush(), txStateStorage.flush())
                .whenComplete((unused, throwable) -> doneClo.accept(throwable));
    }

    @Override
    public boolean onSnapshotLoad(Path path) {
        return true;
    }

    @Override
    public void onShutdown() {
        storage.close();
    }

    @Override
    public void onLeaderStart() {
        maxObservableSafeTime = txManager.clock().now().addPhysicalTime(CLOCK_SKEW).longValue();
    }

    @Override
    public boolean onBeforeApply(Command command) {
        // This method is synchronized by replication group specific monitor, see ActionRequestProcessor#handleRequest.
        if (command instanceof SafeTimePropagatingCommand) {
            SafeTimePropagatingCommand cmd = (SafeTimePropagatingCommand) command;
            long proposedSafeTime = cmd.safeTime().longValue();

            // Because of clock.tick it's guaranteed that two different commands will have different safe timestamps.
            // maxObservableSafeTime may match proposedSafeTime only if it is the command that was previously validated and then retried
            // by raft client because of either TimeoutException or inner raft server recoverable exception.
            if (proposedSafeTime >= maxObservableSafeTime) {
                maxObservableSafeTime = proposedSafeTime;
            } else {
                throw new SafeTimeReorderException();
            }
        }

        return false;
    }

    /**
     * Returns underlying storage.
     */
    @TestOnly
    public MvPartitionStorage getMvStorage() {
        return storage.getStorage();
    }

    /**
     * Handler for the {@link BuildIndexCommand}.
     *
     * @param cmd Command.
     * @param commandIndex RAFT index of the command.
     * @param commandTerm RAFT term of the command.
     */
    void handleBuildIndexCommand(BuildIndexCommand cmd, long commandIndex, long commandTerm) {
        // Skips the write command because the storage has already executed it.
        if (commandIndex <= storage.lastAppliedIndex()) {
            return;
        }

        BuildIndexRowVersionChooser rowVersionChooser = createBuildIndexRowVersionChooser(cmd);

        storage.runConsistently(locker -> {
            List<UUID> rowUuids = new ArrayList<>(cmd.rowIds());

            // Natural UUID order matches RowId order within the same partition.
            Collections.sort(rowUuids);

            Stream<BinaryRowAndRowId> buildIndexRowStream = createBuildIndexRowStream(rowUuids, locker, rowVersionChooser);

            RowId nextRowIdToBuild = cmd.finish() ? null : toRowId(requireNonNull(last(rowUuids))).increment();

            storageUpdateHandler.getIndexUpdateHandler().buildIndex(cmd.indexId(), buildIndexRowStream, nextRowIdToBuild);

            storage.lastApplied(commandIndex, commandTerm);

            return null;
        });

        if (cmd.finish()) {
            LOG.info(
                    "Finish building the index: [tableId={}, partitionId={}, indexId={}]",
                    storage.tableId(), storage.partitionId(), cmd.indexId()
            );
        }
    }

    private static void onTxStateStorageCasFail(UUID txId, TxMeta txMetaBeforeCas, TxMeta txMetaToSet) {
        String errorMsg = format("Failed to update tx state in the storage, transaction txId = {} because of inconsistent state,"
                        + " expected state = {}, state to set = {}",
                txId,
                txMetaBeforeCas,
                txMetaToSet
        );

        IgniteInternalException stateChangeException =
                new UnexpectedTransactionStateException(
                        errorMsg,
                        new TransactionResult(txMetaBeforeCas.txState(), txMetaBeforeCas.commitTimestamp())
                );

        // Exception is explicitly logged because otherwise it can be lost if it did not occur on the leader.
        LOG.error(errorMsg);

        throw stateChangeException;
    }

    private static <T extends Comparable<T>> void updateTrackerIgnoringTrackerClosedException(
            PendingComparableValuesTracker<T, Void> tracker,
            T newValue
    ) {
        try {
            tracker.update(newValue, null);
        } catch (TrackerClosedException ignored) {
            // No-op.
        }
    }

    private Stream<BinaryRowAndRowId> createBuildIndexRowStream(
            List<UUID> rowUuids,
            Locker locker,
            BuildIndexRowVersionChooser rowVersionChooser
    ) {
        return rowUuids.stream()
                .map(this::toRowId)
                .peek(locker::lock)
                .map(rowVersionChooser::chooseForBuildIndex)
                .flatMap(Collection::stream);
    }

    private RowId toRowId(UUID rowUuid) {
        return new RowId(storageUpdateHandler.partitionId(), rowUuid);
    }

    private void replicaTouch(UUID txId, String txCoordinatorId, HybridTimestamp commitTimestamp, boolean full) {
        txManager.updateTxMeta(txId, old -> new TxStateMeta(
                full ? COMMITTED : PENDING,
                txCoordinatorId,
                old == null ? null : old.commitPartitionId(),
                full ? commitTimestamp : null
        ));
    }

    private void markFinished(UUID txId, boolean commit, @Nullable HybridTimestamp commitTimestamp) {
        txManager.updateTxMeta(txId, old -> new TxStateMeta(
                commit ? COMMITTED : ABORTED,
                old == null ? null : old.txCoordinatorId(),
                old == null ? null : old.commitPartitionId(),
                commit ? commitTimestamp : null
        ));
    }

    private BuildIndexRowVersionChooser createBuildIndexRowVersionChooser(BuildIndexCommand command) {
        int indexCreationCatalogVersion = command.creationCatalogVersion();
        Catalog indexCreationCatalog = catalogService.catalog(indexCreationCatalogVersion);

        assert indexCreationCatalog != null : "indexId=" + command.indexId() + ", catalogVersion=" + indexCreationCatalogVersion;

        int startBuildingIndexCatalogVersion = command.requiredCatalogVersion();

        Catalog startBuildingIndexCatalog = catalogService.catalog(startBuildingIndexCatalogVersion);

        assert startBuildingIndexCatalog != null : "indexId=" + command.indexId() + ", catalogVersion=" + startBuildingIndexCatalogVersion;

        return new BuildIndexRowVersionChooser(storage, indexCreationCatalog.time(), startBuildingIndexCatalog.time());
    }
}
