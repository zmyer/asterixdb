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
package org.apache.asterix.server.test;

import static org.apache.asterix.server.test.NCServiceExecutionIT.APP_HOME;
import static org.apache.asterix.server.test.NCServiceExecutionIT.ASTERIX_APP_DIR;
import static org.apache.asterix.server.test.NCServiceExecutionIT.INSTANCE_DIR;
import static org.apache.asterix.server.test.NCServiceExecutionIT.LOG_DIR;
import static org.apache.asterix.server.test.NCServiceExecutionIT.TARGET_DIR;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.asterix.test.base.RetainLogsRule;
import org.apache.asterix.test.common.TestExecutor;
import org.apache.asterix.testframework.context.TestCaseContext;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hyracks.server.process.HyracksVirtualCluster;
import org.apache.hyracks.util.file.FileUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class NcLifecycleIT {

    private static final String PATH_BASE =
            FileUtil.joinPath("src", "test", "resources", "integrationts", "NcLifecycle");
    private static final String CONF_DIR =
            StringUtils.join(new String[] { TARGET_DIR, "test-classes", "NcLifecycleIT" }, File.separator);
    private static final String PATH_ACTUAL = FileUtil.joinPath("target", "ittest");
    private static final Logger LOGGER = LogManager.getLogger();
    private static String reportPath = new File(FileUtil.joinPath("target", "failsafe-reports")).getAbsolutePath();
    private static final TestExecutor testExecutor = new TestExecutor();
    private static HyracksVirtualCluster cluster;

    private TestCaseContext tcCtx;

    public NcLifecycleIT(TestCaseContext tcCtx) {
        this.tcCtx = tcCtx;
    }

    @Rule
    public TestRule retainLogs = new RetainLogsRule(NCServiceExecutionIT.ASTERIX_APP_DIR, reportPath, this);

    @Before
    public void before() throws Exception {
        LOGGER.info("Creating new instance...");
        File instanceDir = new File(INSTANCE_DIR);
        if (instanceDir.isDirectory()) {
            FileUtils.deleteDirectory(instanceDir);
        }

        cluster = new HyracksVirtualCluster(new File(APP_HOME), new File(ASTERIX_APP_DIR));
        cluster.addNCService(new File(CONF_DIR, "ncservice1.conf"), new File(LOG_DIR, "ncservice1.log"));
        cluster.addNCService(new File(CONF_DIR, "ncservice2.conf"), new File(LOG_DIR, "ncservice2.log"));

        // Start CC
        cluster.start(new File(CONF_DIR, "cc.conf"), new File(LOG_DIR, "cc.log"));
        LOGGER.info("Instance created.");
        testExecutor.waitForClusterActive(30, TimeUnit.SECONDS);
        LOGGER.info("Instance is in ACTIVE state.");
    }

    @After
    public void after() {
        LOGGER.info("Destroying instance...");
        cluster.stop();
        LOGGER.info("Instance destroyed.");
    }

    @Test
    public void test() throws Exception {
        testExecutor.executeTest(PATH_ACTUAL, tcCtx, null, false);
    }

    @Parameterized.Parameters(name = "NcLifecycleIT {index}: {0}")
    public static Collection<Object[]> tests() throws Exception {
        Collection<Object[]> testArgs = buildTestsInXml(TestCaseContext.DEFAULT_TESTSUITE_XML_NAME);
        if (testArgs.size() == 0) {
            testArgs = buildTestsInXml(TestCaseContext.DEFAULT_TESTSUITE_XML_NAME);
        }
        return testArgs;
    }

    private static Collection<Object[]> buildTestsInXml(String xmlfile) throws Exception {
        Collection<Object[]> testArgs = new ArrayList<>();
        TestCaseContext.Builder b = new TestCaseContext.Builder();
        for (TestCaseContext ctx : b.build(new File(PATH_BASE), xmlfile)) {
            testArgs.add(new Object[] { ctx });
        }
        return testArgs;
    }
}
