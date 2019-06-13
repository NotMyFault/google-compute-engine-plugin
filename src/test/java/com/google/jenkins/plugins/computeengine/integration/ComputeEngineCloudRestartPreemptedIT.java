/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jenkins.plugins.computeengine.integration;

import com.google.common.collect.Lists;
import com.google.jenkins.plugins.computeengine.ComputeEngineCloud;
import com.google.jenkins.plugins.computeengine.ComputeEngineComputer;
import com.google.jenkins.plugins.computeengine.InstanceConfiguration;
import com.google.jenkins.plugins.computeengine.client.ComputeClient;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProvisioner.PlannedNode;
import lombok.extern.java.Log;
import org.awaitility.Awaitility;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.google.jenkins.plugins.computeengine.integration.ITUtil.DEB_JAVA_STARTUP_SCRIPT;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.LABEL;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NULL_TEMPLATE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.NUM_EXECUTORS;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.PROJECT_ID;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.TEST_TIMEOUT_MULTIPLIER;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.ZONE;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.getLabel;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initClient;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCloud;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.initCredentials;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.instanceConfigurationBuilder;
import static com.google.jenkins.plugins.computeengine.integration.ITUtil.teardownResources;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Integration test suite for {@link ComputeEngineCloud}. 
 * Verifies that instances with preempted flag will be restarted when preempted.
 */
@Log
public class ComputeEngineCloudRestartPreemptedIT {

  @ClassRule
  public static Timeout timeout = new Timeout(15 * TEST_TIMEOUT_MULTIPLIER, TimeUnit.MINUTES);

  @ClassRule public static JenkinsRule jenkinsRule = new JenkinsRule();

  private static ComputeClient client;
  private static Map<String, String> label =
      getLabel(ComputeEngineCloudRestartPreemptedIT.class);
  private static Collection<PlannedNode> planned;

  @BeforeClass
  public static void init() throws Exception {
    log.info("init");
    initCredentials(jenkinsRule);
    ComputeEngineCloud cloud = initCloud(jenkinsRule);
    client = initClient(jenkinsRule, label, log);

    InstanceConfiguration configuration =
        instanceConfigurationBuilder()
            .startupScript(DEB_JAVA_STARTUP_SCRIPT)
            .numExecutorsStr(NUM_EXECUTORS)
            .labels(LABEL)
            .template(NULL_TEMPLATE)
            .preemptible(true)
            .googleLabels(label)
            .build();

    cloud.setConfigurations(Lists.newArrayList(configuration));

    planned = cloud.provision(new LabelAtom(LABEL), 1);
  }

  @AfterClass
  public static void teardown() throws IOException {
    teardownResources(client, label, log);
  }

  @Test
  public void testIfNodeWasPreempted() throws Exception {
    Iterator<PlannedNode> iterator = planned.iterator();
    PlannedNode plannedNode = iterator.next();
    String name = plannedNode.displayName;
    plannedNode.future.get();
    Node node = jenkinsRule.jenkins.getNode(name);
    
    ComputeEngineComputer computer = (ComputeEngineComputer) node.toComputer();
    assertTrue("Configuration was set as preemptible but saw as not", computer.getPreemptible());

    System.out.println("Sending mainatanance event to " + name);
    client.simulateMaintenanceEvent(PROJECT_ID, ZONE, name);
    Awaitility.await()
            .timeout(1, TimeUnit.MINUTES)
            .until(computer::getPreempted);
  }
}
