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

package org.apache.ignite.client.handler.requests.compute;

import static org.apache.ignite.client.handler.requests.compute.ClientComputeGetStatusRequest.packJobStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.client.handler.NotificationSender;
import org.apache.ignite.compute.DeploymentUnit;
import org.apache.ignite.compute.JobExecution;
import org.apache.ignite.compute.JobExecutionOptions;
import org.apache.ignite.compute.NodeNotFoundException;
import org.apache.ignite.internal.client.proto.ClientMessagePacker;
import org.apache.ignite.internal.client.proto.ClientMessageUnpacker;
import org.apache.ignite.internal.compute.IgniteComputeInternal;
import org.apache.ignite.internal.network.ClusterService;
import org.apache.ignite.network.ClusterNode;

/**
 * Compute execute request.
 */
public class ClientComputeExecuteRequest {
    /**
     * Processes the request.
     *
     * @param in Unpacker.
     * @param out Packer.
     * @param compute Compute.
     * @param cluster Cluster.
     * @param notificationSender Notification sender.
     * @return Future.
     */
    public static CompletableFuture<Void> process(
            ClientMessageUnpacker in,
            ClientMessagePacker out,
            IgniteComputeInternal compute,
            ClusterService cluster,
            NotificationSender notificationSender
    ) {
        Set<ClusterNode> candidates = unpackCandidateNodes(in, cluster);

        List<DeploymentUnit> deploymentUnits = unpackDeploymentUnits(in);
        String jobClassName = in.unpackString();
        JobExecutionOptions options = JobExecutionOptions.builder().priority(in.unpackInt()).maxRetries(in.unpackInt()).build();
        Object[] args = unpackArgs(in);

        JobExecution<Object> execution = compute.executeAsyncWithFailover(candidates, deploymentUnits, jobClassName, options, args);
        sendResultAndStatus(execution, notificationSender);
        return execution.idAsync().thenAccept(out::packUuid);
    }

    private static Set<ClusterNode> unpackCandidateNodes(ClientMessageUnpacker in, ClusterService cluster) {
        int size = in.unpackInt();

        if (size < 1) {
            throw new IllegalArgumentException("nodes must not be empty.");
        }

        Set<String> nodeNames = new HashSet<>(size);
        Set<ClusterNode> nodes = new HashSet<>(size);

        for (int i = 0; i < size; i++) {
            String nodeName = in.unpackString();
            nodeNames.add(nodeName);
            ClusterNode node = cluster.topologyService().getByConsistentId(nodeName);
            if (node != null) {
                nodes.add(node);
            }
        }

        if (nodes.isEmpty()) {
            throw new NodeNotFoundException(nodeNames);
        }

        return nodes;
    }

    static void sendResultAndStatus(JobExecution<Object> execution, NotificationSender notificationSender) {
        execution.resultAsync().whenComplete((val, err) ->
                execution.statusAsync().whenComplete((status, errStatus) ->
                        notificationSender.sendNotification(w -> {
                            w.packObjectAsBinaryTuple(val);
                            packJobStatus(w, status);
                        }, err)));
    }

    /**
     * Unpacks args.
     *
     * @param in Unpacker.
     * @return Args array.
     */
    static Object[] unpackArgs(ClientMessageUnpacker in) {
        return in.unpackObjectArrayFromBinaryTuple();
    }

    /**
     * Unpacks deployment units.
     *
     * @param in Unpacker.
     * @return Deployment units.
     */
    static List<DeploymentUnit> unpackDeploymentUnits(ClientMessageUnpacker in) {
        int size = in.tryUnpackNil() ? 0 : in.unpackInt();
        List<DeploymentUnit> res = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            res.add(new DeploymentUnit(in.unpackString(), in.unpackString()));
        }

        return res;
    }
}
