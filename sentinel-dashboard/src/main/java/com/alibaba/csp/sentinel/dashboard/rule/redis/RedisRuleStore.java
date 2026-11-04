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

import java.time.Duration;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.StringUtil;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stores rules (JSON) of each app and rule type in Redis, and publishes every change
 * to the corresponding channel so that clients using {@code RedisDataSource} receive it.
 */
@Component
public class RedisRuleStore {

    private final Logger logger = LoggerFactory.getLogger(RedisRuleStore.class);

    private final RedisRuleProperties properties;

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;

    public RedisRuleStore(RedisRuleProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            return;
        }
        RedisURI.Builder builder = RedisURI.builder()
            .withHost(properties.getHost())
            .withPort(properties.getPort())
            .withDatabase(properties.getDatabase())
            .withTimeout(Duration.ofMillis(properties.getTimeout()));
        if (StringUtil.isNotEmpty(properties.getPassword())) {
            builder.withPassword(properties.getPassword());
        }
        redisClient = RedisClient.create(builder.build());
        connection = redisClient.connect();
        logger.info("Redis rule store enabled, redis: {}:{}/{}, keyPrefix: {}, channelPrefix: {}",
            properties.getHost(), properties.getPort(), properties.getDatabase(),
            properties.getKeyPrefix(), properties.getChannelPrefix());
    }

    public boolean isEnabled() {
        return connection != null;
    }

    public String getRuleKey(String app, String type) {
        return properties.getKeyPrefix() + app + ":" + type;
    }

    public String getChannel(String app, String type) {
        return properties.getChannelPrefix() + app + ":" + type;
    }

    /**
     * @return rules of the app in JSON, or null if absent
     */
    public synchronized String getRules(String app, String type) {
        checkState(app, type);
        return connection.sync().get(getRuleKey(app, type));
    }

    public synchronized void publishRules(String app, String type, String rules) {
        checkState(app, type);
        AssertUtil.notNull(rules, "rules cannot be null");
        RedisCommands<String, String> commands = connection.sync();
        commands.multi();
        commands.set(getRuleKey(app, type), rules);
        commands.publish(getChannel(app, type), rules);
        commands.exec();
    }

    private void checkState(String app, String type) {
        if (!isEnabled()) {
            throw new IllegalStateException("Redis rule store is not enabled");
        }
        AssertUtil.notEmpty(app, "app cannot be empty");
        AssertUtil.notEmpty(type, "rule type cannot be empty");
    }

    @PreDestroy
    public void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        if (redisClient != null) {
            redisClient.shutdown();
            redisClient = null;
        }
    }
}
