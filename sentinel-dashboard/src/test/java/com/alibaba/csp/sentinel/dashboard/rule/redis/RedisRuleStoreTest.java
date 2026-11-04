/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.dashboard.rule.redis;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ai.grakn.redismock.RedisServer;
import com.alibaba.csp.sentinel.dashboard.client.SentinelApiClient;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.FlowRuleEntity;
import com.alibaba.csp.sentinel.datasource.redis.RedisDataSource;
import com.alibaba.csp.sentinel.datasource.redis.config.RedisConnectionConfig;
import com.alibaba.csp.sentinel.property.PropertyListener;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RedisRuleStoreTest {

    private static final String APP = "test-app";

    private RedisServer server;
    private RedisRuleStore store;
    private SentinelApiClient apiClient;
    private RedisDataSource<List<FlowRule>> dataSource;

    @Before
    public void setUp() throws Exception {
        server = RedisServer.newRedisServer();
        server.start();
        RedisRuleProperties properties = new RedisRuleProperties();
        properties.setEnabled(true);
        properties.setHost(server.getHost());
        properties.setPort(server.getBindPort());
        store = new RedisRuleStore(properties);
        store.init();
        apiClient = new SentinelApiClient();
        ReflectionTestUtils.setField(apiClient, "redisRuleStore", store);
    }

    @After
    public void tearDown() throws Exception {
        if (dataSource != null) {
            dataSource.close();
        }
        apiClient.close();
        store.close();
        server.stop();
    }

    @Test
    public void testDisabledByDefault() {
        RedisRuleStore disabled = new RedisRuleStore(new RedisRuleProperties());
        disabled.init();
        assertFalse(disabled.isEnabled());
    }

    @Test
    public void testPublishAndGetRules() {
        assertTrue(store.isEnabled());
        assertNull(store.getRules(APP, "flow"));
        store.publishRules(APP, "flow", "[]");
        assertEquals("[]", store.getRules(APP, "flow"));
        assertEquals("sentinel:rule:test-app:flow", store.getRuleKey(APP, "flow"));
        assertEquals("sentinel:channel:test-app:flow", store.getChannel(APP, "flow"));
    }

    @Test
    public void testSentinelApiClientUsesRedisAndClientReceivesRules() {
        RedisConnectionConfig config = RedisConnectionConfig.builder()
            .withHost(server.getHost())
            .withPort(server.getBindPort())
            .build();
        dataSource = new RedisDataSource<>(config, store.getRuleKey(APP, "flow"), store.getChannel(APP, "flow"),
            source -> JSON.parseObject(source, new TypeReference<List<FlowRule>>() {}));
        AtomicReference<List<FlowRule>> received = new AtomicReference<>();
        dataSource.getProperty().addListener(new PropertyListener<List<FlowRule>>() {
            @Override
            public void configUpdate(List<FlowRule> value) {
                received.set(value);
            }

            @Override
            public void configLoad(List<FlowRule> value) {
                received.set(value);
            }
        });

        assertTrue(apiClient.fetchFlowRuleOfMachine(APP, "127.0.0.1", 8719).isEmpty());

        FlowRuleEntity entity = new FlowRuleEntity();
        entity.setApp(APP);
        entity.setIp("127.0.0.1");
        entity.setPort(8719);
        entity.setResource("res");
        entity.setLimitApp("default");
        entity.setGrade(1);
        entity.setCount(10d);
        entity.setStrategy(0);
        entity.setControlBehavior(0);
        assertTrue(apiClient.setFlowRuleOfMachine(APP, "127.0.0.1", 8719, Collections.singletonList(entity)));

        List<FlowRuleEntity> fetched = apiClient.fetchFlowRuleOfMachine(APP, "10.0.0.2", 8720);
        assertEquals(1, fetched.size());
        assertEquals("res", fetched.get(0).getResource());
        assertEquals("10.0.0.2", fetched.get(0).getIp());

        await().atMost(3, TimeUnit.SECONDS).until(() -> {
            List<FlowRule> rules = received.get();
            return rules != null && rules.size() == 1 && "res".equals(rules.get(0).getResource());
        });
    }
}
