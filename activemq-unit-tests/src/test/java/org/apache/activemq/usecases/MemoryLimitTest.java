/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.usecases;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.TestSupport;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.region.Destination;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.util.ConsumerThread;
import org.apache.activemq.util.ProducerThread;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@RunWith(value = Parameterized.class)
public class MemoryLimitTest extends TestSupport {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryLimitTest.class);
    final String payload = new String(new byte[10 * 1024]); //10KB
    protected BrokerService broker;

    private final TestSupport.PersistenceAdapterChoice persistenceAdapterChoice;

    @Parameterized.Parameters
    public static Collection<TestSupport.PersistenceAdapterChoice[]> getTestParameters() {
        TestSupport.PersistenceAdapterChoice[] kahaDb = {TestSupport.PersistenceAdapterChoice.KahaDB};
        TestSupport.PersistenceAdapterChoice[] levelDb = {TestSupport.PersistenceAdapterChoice.LevelDB};
        TestSupport.PersistenceAdapterChoice[] jdbc = {TestSupport.PersistenceAdapterChoice.JDBC};
        List<TestSupport.PersistenceAdapterChoice[]> choices = new ArrayList<TestSupport.PersistenceAdapterChoice[]>();
        choices.add(kahaDb);
        choices.add(levelDb);
        choices.add(jdbc);
        return choices;
    }

    public MemoryLimitTest(TestSupport.PersistenceAdapterChoice choice) {
        this.persistenceAdapterChoice = choice;
    }

    protected BrokerService createBroker() throws Exception {
        BrokerService broker = new BrokerService();
        broker.getSystemUsage().getMemoryUsage().setLimit(1 * 1024 * 1024); //1MB
        broker.deleteAllMessages();

        PolicyMap policyMap = new PolicyMap();
        PolicyEntry policyEntry = new PolicyEntry();
        policyEntry.setProducerFlowControl(false);
        policyMap.put(new ActiveMQQueue(">"), policyEntry);
        broker.setDestinationPolicy(policyMap);

        LOG.info("Starting broker with persistenceAdapterChoice " + persistenceAdapterChoice.toString());
        setPersistenceAdapter(broker, persistenceAdapterChoice);
        broker.getPersistenceAdapter().deleteAllMessages();

        return broker;
    }

    @Before
    public void setUp() throws Exception {
        if (broker == null) {
            broker = createBroker();
        }
        broker.start();
        broker.waitUntilStarted();
    }

    @After
    public void tearDown() throws Exception {
        if (broker != null) {
            broker.stop();
            broker.waitUntilStopped();
        }
    }

    @Test(timeout = 120000)
    public void testCursorBatch() throws Exception {

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("vm://localhost?jms.prefetchPolicy.all=10");
        factory.setOptimizeAcknowledge(true);
        Connection conn = factory.createConnection();
        conn.start();
        Session sess = conn.createSession(false, Session.CLIENT_ACKNOWLEDGE);
        Queue queue = sess.createQueue("STORE");
        final ProducerThread producer = new ProducerThread(sess, queue) {
            @Override
            protected Message createMessage(int i) throws Exception {
                return sess.createTextMessage(payload + "::" + i);
            }
        };
        producer.setMessageCount(2000);
        producer.start();
        producer.join();

        Thread.sleep(1000);

        // assert we didn't break high watermark (70%) usage
        Destination dest = broker.getDestination((ActiveMQQueue) queue);
        LOG.info("Destination usage: " + dest.getMemoryUsage());
        assertTrue(dest.getMemoryUsage().getPercentUsage() <= 71);
        LOG.info("Broker usage: " + broker.getSystemUsage().getMemoryUsage());
        assertTrue(broker.getSystemUsage().getMemoryUsage().getPercentUsage() <= 71);

        // consume one message
        MessageConsumer consumer = sess.createConsumer(queue);
        Message msg = consumer.receive();
        msg.acknowledge();

        Thread.sleep(1000);
        // this should free some space and allow us to get new batch of messages in the memory
        // exceeding the limit
        LOG.info("Destination usage: " + dest.getMemoryUsage());
        assertTrue(dest.getMemoryUsage().getPercentUsage() >= 478);
        LOG.info("Broker usage: " + broker.getSystemUsage().getMemoryUsage());
        assertTrue(broker.getSystemUsage().getMemoryUsage().getPercentUsage() >= 478);

        // let's make sure we can consume all messages
        for (int i = 1; i < 2000; i++) {
            msg = consumer.receive(1000);
            assertNotNull("Didn't receive message " + i, msg);
            msg.acknowledge();
        }

    }

    /**
     *
     * Handy test for manually checking what's going on
     *
     */
    @Ignore
    @Test(timeout = 120000)
    public void testLimit() throws Exception {

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("vm://localhost?jms.prefetchPolicy.all=10");
        factory.setOptimizeAcknowledge(true);
        Connection conn = factory.createConnection();
        conn.start();
        Session sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        final ProducerThread producer = new ProducerThread(sess, sess.createQueue("STORE.1")) {
            @Override
            protected Message createMessage(int i) throws Exception {
                return sess.createTextMessage(payload + "::" + i);
            }
        };
        producer.setMessageCount(1000);

        final ProducerThread producer2 = new ProducerThread(sess, sess.createQueue("STORE.2")) {
            @Override
            protected Message createMessage(int i) throws Exception {
                return sess.createTextMessage(payload + "::" + i);
            }
        };
        producer2.setMessageCount(1000);


        ConsumerThread consumer = new ConsumerThread(sess, sess.createQueue("STORE.1"));
        consumer.setBreakOnNull(false);
        consumer.setMessageCount(1000);

        producer.start();
        producer.join();

        producer2.start();

        Thread.sleep(300);

        consumer.start();

        consumer.join();
        producer2.join();

        assertEquals("consumer got all produced messages", producer.getMessageCount(), consumer.getReceived());

    }
}
