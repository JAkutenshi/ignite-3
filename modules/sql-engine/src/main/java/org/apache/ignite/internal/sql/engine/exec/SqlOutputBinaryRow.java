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

package org.apache.ignite.internal.sql.engine.exec;

import static org.apache.ignite.internal.sql.engine.util.Commons.readValue;

import java.nio.ByteBuffer;
import org.apache.ignite.internal.binarytuple.BinaryTupleReader;
import org.apache.ignite.internal.lang.InternalTuple;
import org.apache.ignite.internal.schema.BinaryRowEx;
import org.apache.ignite.internal.type.NativeType;
import org.apache.ignite.internal.util.ColocationUtils;
import org.apache.ignite.internal.util.HashCalculator;

/**
 * Implementation of {@link BinaryRowEx} used by SQL engine from DML operations.
 */
public class SqlOutputBinaryRow extends BinaryTupleReader implements BinaryRowEx {

    private final int schemaVersion;

    private final int colocationHash;

    private SqlOutputBinaryRow(int schemaVersion, int colocationHash, int numElements, ByteBuffer buffer) {
        super(numElements, buffer);
        this.schemaVersion = schemaVersion;
        this.colocationHash = colocationHash;
    }

    /** {@inheritDoc} */
    @Override
    public int schemaVersion() {
        return schemaVersion;
    }

    /** {@inheritDoc} */
    @Override
    public int tupleSliceLength() {
        return byteBuffer().remaining();
    }

    /** {@inheritDoc} */
    @Override
    public ByteBuffer tupleSlice() {
        return byteBuffer();
    }

    /** {@inheritDoc} */
    @Override
    public int colocationHash() {
        return colocationHash;
    }

    /** Creates BinaryRow from the given tuple. */
    public static SqlOutputBinaryRow newRow(
            int schemaVersion,
            int[] colocationColumnIndexes,
            NativeType[] colocationColumnTypes,
            InternalTuple binaryTuple
    ) {
        HashCalculator hashCalc = new HashCalculator();

        for (int i = 0; i < colocationColumnIndexes.length; i++) {
            NativeType type = colocationColumnTypes[i];

            Object value = readValue(binaryTuple, type, colocationColumnIndexes[i]);

            ColocationUtils.append(hashCalc, value, type);
        }

        int colocationHash = hashCalc.hash();

        return new SqlOutputBinaryRow(schemaVersion, colocationHash, binaryTuple.elementCount(), binaryTuple.byteBuffer());
    }
}
