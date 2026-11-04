# Sentinel 控制台

## 0. 概述

Sentinel 控制台是流量控制、熔断降级规则统一配置和管理的入口，它为用户提供了机器自发现、簇点链路自发现、监控、规则配置等功能。在 Sentinel 控制台上，我们可以配置规则并实时查看流量控制效果。

## 1. 编译和启动

### 1.1 如何编译

使用如下命令将代码打包成一个 fat jar:

```bash
mvn clean package
```

### 1.2 如何启动

使用如下命令启动编译后的控制台：

```bash
java -Dserver.port=8080 \
-Dcsp.sentinel.dashboard.server=localhost:8080 \
-Dproject.name=sentinel-dashboard \
-jar target/sentinel-dashboard.jar
```

上述命令中我们指定几个 JVM 参数，其中 `-Dserver.port=8080` 是 Spring Boot 的参数，
用于指定 Spring Boot 服务端启动端口为 `8080`。其余几个是 Sentinel 客户端的参数。

为便于演示，我们对控制台本身加入了流量控制功能，具体做法是引入 Sentinel 提供的 `CommonFilter` 这个 Servlet Filter。
上述 JVM 参数的含义是：

| 参数 | 作用 |
|--------|--------|
|`Dcsp.sentinel.dashboard.server=localhost:8080`|向 Sentinel 接入端指定控制台的地址|
|`-Dproject.name=sentinel-dashboard`|向 Sentinel 指定应用名称，比如上面对应的应用名称就为 `sentinel-dashboard`|

全部的配置项可以参考 [启动配置项文档](https://github.com/alibaba/Sentinel/wiki/%E5%90%AF%E5%8A%A8%E9%85%8D%E7%BD%AE%E9%A1%B9)。

经过上述配置，控制台启动后会自动向自己发送心跳。程序启动后浏览器访问 `localhost:8080` 即可访问 Sentinel 控制台。

从 Sentinel 1.6.0 开始，Sentinel 控制台支持简单的**登录**功能，默认用户名和密码都是 `sentinel`。用户可以通过如下参数进行配置：

- `-Dsentinel.dashboard.auth.username=sentinel` 用于指定控制台的登录用户名为 `sentinel`；
- `-Dsentinel.dashboard.auth.password=123456` 用于指定控制台的登录密码为 `123456`；如果省略这两个参数，默认用户和密码均为 `sentinel`；
- `-Dserver.servlet.session.timeout=7200` 用于指定 Spring Boot 服务端 session 的过期时间，如 `7200` 表示 7200 秒；`60m` 表示 60 分钟，默认为 30 分钟；

### 1.3 规则持久化到 Redis（可选）

默认情况下控制台的规则只保存在内存中并直接推送到客户端机器，控制台或客户端重启后规则会丢失。
开启 Redis 规则持久化后，控制台对 **流控 / 降级 / 系统 / 授权 / 热点** 规则的查询和修改都会改为读写 Redis：

- 每个应用、每种规则对应一个 key：`{key-prefix}{app}:{type}`，值为规则列表的 JSON；
- 每次修改规则后会（在同一个事务中）`SET` 该 key 并 `PUBLISH` 到 channel：`{channel-prefix}{app}:{type}`；
- `type` 取值：`flow`、`degrade`、`system`、`authority`、`param-flow`；
- 规则按应用维度存储，同一应用的所有机器共享同一份规则。

启动参数示例（也可以写在 `application.properties`，或用环境变量如 `SENTINEL_RULE_REDIS_ENABLED=true`）：

```bash
java -Dsentinel.rule.redis.enabled=true \
-Dsentinel.rule.redis.host=127.0.0.1 \
-Dsentinel.rule.redis.port=6379 \
-jar target/sentinel-dashboard.jar
```

| 配置项 | 默认值 | 说明 |
|--------|--------|--------|
| `sentinel.rule.redis.enabled` | `false` | 是否开启 Redis 规则持久化 |
| `sentinel.rule.redis.host` | `localhost` | Redis 地址 |
| `sentinel.rule.redis.port` | `6379` | Redis 端口 |
| `sentinel.rule.redis.password` | 空 | Redis 密码 |
| `sentinel.rule.redis.database` | `0` | Redis 库 |
| `sentinel.rule.redis.timeout` | `3000` | 超时时间（毫秒） |
| `sentinel.rule.redis.key-prefix` | `sentinel:rule:` | 规则 key 前缀 |
| `sentinel.rule.redis.channel-prefix` | `sentinel:channel:` | 规则变更 channel 前缀 |

客户端需引入 `sentinel-datasource-redis`，并使用相同的 key / channel 注册 `RedisDataSource`，例如（应用名为 `my-app`）：

```java
RedisConnectionConfig config = RedisConnectionConfig.builder()
    .withHost("127.0.0.1")
    .withPort(6379)
    .build();

ReadableDataSource<String, List<FlowRule>> flowRuleDataSource = new RedisDataSource<>(config,
    "sentinel:rule:my-app:flow", "sentinel:channel:my-app:flow",
    source -> JSON.parseObject(source, new TypeReference<List<FlowRule>>() {}));
FlowRuleManager.register2Property(flowRuleDataSource.getProperty());

ReadableDataSource<String, List<DegradeRule>> degradeRuleDataSource = new RedisDataSource<>(config,
    "sentinel:rule:my-app:degrade", "sentinel:channel:my-app:degrade",
    source -> JSON.parseObject(source, new TypeReference<List<DegradeRule>>() {}));
DegradeRuleManager.register2Property(degradeRuleDataSource.getProperty());
// system -> SystemRuleManager, authority -> AuthorityRuleManager, param-flow -> ParamFlowRuleManager
```

> 注意：开启后控制台不再通过 HTTP 直接向客户端推送规则，客户端必须按上述方式订阅 Redis 才能让规则生效。

## 2. 客户端接入

选择合适的方式接入 Sentinel，然后在应用启动时加入 JVM 参数 `-Dcsp.sentinel.dashboard.server=consoleIp:port` 指定控制台地址和端口。
确保客户端有访问量，**Sentinel 会在客户端首次调用的时候进行初始化，开始向控制台发送心跳包**，将客户端纳入到控制台的管辖之下。

客户端接入的详细步骤请参考 [Wiki 文档](https://github.com/alibaba/Sentinel/wiki/%E6%8E%A7%E5%88%B6%E5%8F%B0#3-%E5%AE%A2%E6%88%B7%E7%AB%AF%E6%8E%A5%E5%85%A5%E6%8E%A7%E5%88%B6%E5%8F%B0)。

## 3. 验证是否接入成功

客户端正确配置并启动后，会**在初次调用后**主动向控制台发送心跳包，汇报自己的存在；
控制台收到客户端心跳包之后，会在左侧导航栏中显示该客户端信息。如果控制台能够看到客户端的机器信息，则表明客户端接入成功了。

更多：[控制台功能介绍](./Sentinel_Dashboard_Feature.md)。