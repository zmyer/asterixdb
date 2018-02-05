/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.control.nc.dataset;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.apache.hyracks.api.comm.IFrameWriter;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.partitions.ResultSetPartitionId;
import org.apache.hyracks.comm.channels.NetworkOutputChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DatasetPartitionReader {
    private static final Logger LOGGER = LogManager.getLogger();

    private final DatasetPartitionManager datasetPartitionManager;
    private final DatasetMemoryManager datasetMemoryManager;
    private final Executor executor;
    private final ResultState resultState;

    public DatasetPartitionReader(DatasetPartitionManager datasetPartitionManager,
            DatasetMemoryManager datasetMemoryManager, Executor executor, ResultState resultState) {
        this.datasetPartitionManager = datasetPartitionManager;
        this.datasetMemoryManager = datasetMemoryManager;
        this.executor = executor;
        this.resultState = resultState;
    }

    public void writeTo(final IFrameWriter writer) {
        executor.execute(new ResultPartitionSender((NetworkOutputChannel) writer));
    }

    private class ResultPartitionSender implements Runnable {

        private final NetworkOutputChannel channel;

        ResultPartitionSender(final NetworkOutputChannel channel) {
            this.channel = channel;
        }

        @Override
        public void run() {
            channel.setFrameSize(resultState.getFrameSize());
            channel.open();
            try {
                resultState.readOpen();
                long offset = 0;
                final ByteBuffer buffer = ByteBuffer.allocate(resultState.getFrameSize());
                while (true) {
                    buffer.clear();
                    final long size = read(offset, buffer);
                    if (size <= 0) {
                        break;
                    } else if (size < buffer.limit()) {
                        throw new IllegalStateException(
                                "Premature end of file - readSize: " + size + " buffer limit: " + buffer.limit());
                    }
                    offset += size;
                    buffer.flip();
                    channel.nextFrame(buffer);
                }
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("result reading successful(" + resultState.getResultSetPartitionId() + ")");
                }
            } catch (Exception e) {
                LOGGER.error(() -> "failed to send result partition " + resultState.getResultSetPartitionId(), e);
                channel.abort();
            } finally {
                close();
            }
        }

        private long read(long offset, ByteBuffer buffer) throws HyracksDataException {
            return datasetMemoryManager != null ? resultState.read(datasetMemoryManager, offset, buffer)
                    : resultState.read(offset, buffer);
        }

        private void close() {
            try {
                channel.close();
                resultState.readClose();
                if (resultState.isExhausted()) {
                    final ResultSetPartitionId partitionId = resultState.getResultSetPartitionId();
                    datasetPartitionManager.removePartition(partitionId.getJobId(), partitionId.getResultSetId(),
                            partitionId.getPartition());
                }
            } catch (HyracksDataException e) {
                LOGGER.error("unexpected failure in partition reader clean up", e);
            }
        }
    }
}
