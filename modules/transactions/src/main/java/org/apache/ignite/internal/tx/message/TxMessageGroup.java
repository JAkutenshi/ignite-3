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

package org.apache.ignite.internal.tx.message;

import org.apache.ignite.internal.network.annotations.MessageGroup;

/**
 * Message types for transactions.
 */
@MessageGroup(groupType = 5, groupName = "TxMessages")
public class TxMessageGroup {
    /**
     * Message type for {@link TxFinishReplicaRequest}.
     */
    public static final short TX_FINISH_REQUEST = 0;

    /**
     * Message type for {@link TxFinishResponse}.
     */
    public static final short TX_FINISH_RESPONSE = 1;

    /**
     * Message type for {@link WriteIntentSwitchReplicaRequest}.
     */
    public static final short WRITE_INTENT_SWITCH_REQUEST = 2;

    /**
     * Message type for {@link TxStateCommitPartitionRequest}.
     */
    public static final short TX_STATE_COMMIT_PARTITION_REQUEST = 3;

    /**
     * Message type for {@link TxStateCoordinatorRequest}.
     */
    public static final short TX_STATE_COORDINATOR_REQUEST = 4;

    /**
     * Message type for {@link TxStateResponse}.
     */
    public static final short TX_STATE_RESPONSE = 5;

    /**
     * Message type for {@link TxRecoveryMessage}.
     */
    public static final short TX_RECOVERY_MSG = 6;

    /**
     * Message type for {@link TxCleanupMessage}.
     */
    public static final short TX_CLEANUP_MSG = 7;

    /**
     * Message type for {@link TxCleanupMessageResponse}.
     */
    public static final short TX_CLEANUP_MSG_RESPONSE = 8;

    /**
     * Message type for {@link TxCleanupMessageErrorResponse}.
     */
    public static final short TX_CLEANUP_MSG_ERR_RESPONSE = 9;
}
