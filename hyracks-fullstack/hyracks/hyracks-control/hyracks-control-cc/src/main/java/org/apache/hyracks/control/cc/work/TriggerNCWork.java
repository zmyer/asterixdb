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
package org.apache.hyracks.control.cc.work;

import static org.apache.hyracks.control.common.controllers.ServiceConstants.NC_SERVICE_MAGIC_COOKIE;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.StringWriter;
import java.net.Socket;

import org.apache.hyracks.api.config.Section;
import org.apache.hyracks.control.cc.ClusterControllerService;
import org.apache.hyracks.control.common.controllers.NCConfig;
import org.apache.hyracks.control.common.controllers.ServiceConstants.ServiceCommand;
import org.apache.hyracks.control.common.work.AbstractWork;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ini4j.Ini;

/**
 * A work which is run at CC startup for each NC specified in the configuration file.
 * It contacts the NC service on each node and passes in the NC-specific configuration.
 */
public class TriggerNCWork extends AbstractWork {

    private static final Logger LOGGER = LogManager.getLogger();

    private final ClusterControllerService ccs;
    private final String ncHost;
    private final int ncPort;
    private final String ncId;

    public TriggerNCWork(ClusterControllerService ccs, String ncHost, int ncPort, String ncId) {
        this.ccs = ccs;
        this.ncHost = ncHost;
        this.ncPort = ncPort;
        this.ncId = ncId;
    }

    @Override
    public final void run() {
        ccs.getExecutor().execute(() -> {
            while (true) {
                LOGGER.info("Connecting NC service '" + ncId + "' at " + ncHost + ":" + ncPort);
                try (Socket s = new Socket(ncHost, ncPort)) {
                    ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
                    oos.writeUTF(NC_SERVICE_MAGIC_COOKIE);
                    oos.writeUTF(ServiceCommand.START_NC.name());
                    oos.writeUTF(TriggerNCWork.this.serializeIni(ccs.getCCConfig().getIni()));
                    oos.close();
                    return;
                    // QQQ Should probably have an ACK here
                } catch (IOException e) {
                    LOGGER.log(Level.WARN, "Failed to contact NC service at " + ncHost + ":" + ncPort + "; will retry",
                            e);
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    /**
     * Given an Ini object, serialize it to String with some enhancements.
     * @param ccini the ini file to decorate and forward to NC
     */
    private String serializeIni(Ini ccini) throws IOException {
        StringWriter iniString = new StringWriter();
        ccini.get(Section.NC.sectionName()).putIfAbsent(NCConfig.Option.CLUSTER_ADDRESS.ini(),
                ccs.getCCConfig().getClusterPublicAddress());
        ccini.get(Section.NC.sectionName()).putIfAbsent(NCConfig.Option.CLUSTER_PORT.ini(),
                String.valueOf(ccs.getCCConfig().getClusterPublicPort()));
        // Finally insert *this* NC's name into localnc section - this is a fixed
        // entry point so that NCs can determine where all their config is.
        ccini.put(Section.LOCALNC.sectionName(), NCConfig.Option.NODE_ID.ini(), ncId);
        ccini.store(iniString);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Returning Ini file:\n" + iniString.toString());
        }
        return iniString.toString();
    }
}
