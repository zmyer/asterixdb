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
package org.apache.hyracks.control.nc.work;

import java.util.Collection;

import org.apache.hyracks.api.control.CcId;
import org.apache.hyracks.api.dataset.IDatasetPartitionManager;
import org.apache.hyracks.api.job.JobStatus;
import org.apache.hyracks.control.common.work.SynchronizableWork;
import org.apache.hyracks.control.nc.Joblet;
import org.apache.hyracks.control.nc.NodeControllerService;
import org.apache.hyracks.control.nc.Task;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AbortAllJobsWork extends SynchronizableWork {

    private static final Logger LOGGER = LogManager.getLogger();
    private final NodeControllerService ncs;
    private final CcId ccId;

    public AbortAllJobsWork(NodeControllerService ncs, CcId ccId) {
        this.ncs = ncs;
        this.ccId = ccId;
    }

    @Override
    protected void doRun() throws Exception {
        LOGGER.info("Aborting all tasks for controller {}", ccId);
        IDatasetPartitionManager dpm = ncs.getDatasetPartitionManager();
        if (dpm == null) {
            LOGGER.log(Level.WARN, "DatasetPartitionManager is null on " + ncs.getId());
        }
        Collection<Joblet> joblets = ncs.getJobletMap().values();
        for (Joblet ji : joblets) {
            // TODO(mblow): should we have one jobletmap per cc?
            if (!ji.getJobId().getCcId().equals(ccId)) {
                continue;
            }
            if (dpm != null) {
                dpm.abortReader(ji.getJobId());
            }
            Collection<Task> tasks = ji.getTaskMap().values();
            for (Task task : tasks) {
                task.abort();
            }
            ncs.getWorkQueue().schedule(new CleanupJobletWork(ncs, ji.getJobId(), JobStatus.FAILURE));
        }
    }
}
