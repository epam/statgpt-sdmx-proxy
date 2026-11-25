# Cloud-Provider Redis Authentication for SDMX Proxy

## Context

Currently, SDMX Proxy connects to Redis using static host/port/password credentials (`REDIS_HOST`, `REDIS_PORT`,
`REDIS_PASSWORD` env vars). This works for local development but is insufficient for cloud deployments where best
practice is to use managed identity / IAM roles for authentication (no static passwords).

We need to support Redis authentication via:

1. **Azure Managed Identity**
2. **AWS IAM Roles**
3. **GCP Service Account**

Reference implementation: `ai-dial-core`'s `CacheClientFactory` + per-cloud `CredentialsResolver` classes (uses
Redisson). We adapt the same pattern to **Lettuce** (Spring Data Redis) using
`io.lettuce.core.RedisCredentialsProvider`.

## Approach

### Configuration Model

New property `sdmxproxy.cache.redis.provider` (env: `REDIS_AUTH_PROVIDER`, default: `NONE`).

```yaml
sdmxproxy:
  cache:
    mode: ${CACHE_MODE:LOCAL}
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      ssl: ${REDIS_SSL:false}
      provider: ${REDIS_AUTH_PROVIDER:NONE}   # NONE | AZURE | AWS | GCP
      aws:
        user-id: ${REDIS_AWS_USER_ID:}
        region: ${REDIS_AWS_REGION:}
        cluster-name: ${REDIS_AWS_CLUSTER_NAME:}
        serverless: ${REDIS_AWS_SERVERLESS:false}
      gcp:
        service-account: ${REDIS_GCP_SERVICE_ACCOUNT:}
```

Azure needs no extra config — `DefaultAzureCredential` auto-detects identity from environment.

### New Files

All under `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/`:

| File                                                    | Purpose                                                                |
|---------------------------------------------------------|------------------------------------------------------------------------|
| `config/RedisAuthProvider.java`                         | Enum: `NONE`, `AZURE`, `AWS`, `GCP`                                    |
| `config/AwsRedisProperties.java`                        | `@Data`: userId, region, clusterName, serverless                       |
| `config/GcpRedisProperties.java`                        | `@Data`: serviceAccount                                                |
| `credentials/AzureRedisCredentialsProvider.java`        | Lettuce `RedisCredentialsProvider` — Azure Managed Identity            |
| `credentials/AwsRedisCredentialsProvider.java`          | Lettuce `RedisCredentialsProvider` — AWS ElastiCache SigV4 IAM auth    |
| `credentials/GcpRedisCredentialsProvider.java`          | Lettuce `RedisCredentialsProvider` — GCP Memorystore IAM access tokens |
| `credentials/IamAuthTokenRequest.java`                  | SigV4-presigned token generator for AWS ElastiCache                    |
| `credentials/ProxyRedisCredentialsProviderFactory.java` | `@Component` — creates the right provider based on config              |

### Modified Files

| File                          | Changes                                                                               |
|-------------------------------|---------------------------------------------------------------------------------------|
| `config/RedisProperties.java` | Add `provider` (enum), `aws`, `gcp` fields                                            |
| `config/RedisConfig.java`     | Wire `RedisCredentialsProvider` + SSL into `LettuceConnectionFactory`                 |
| `application.yaml`            | Add provider config block                                                             |
| `build.gradle`                | Add `azure-identity`, `aws-java-sdk-core`, `google-cloud-iamcredentials` dependencies |

### Implementation Details

#### `AzureRedisCredentialsProvider` (core of Phase 1)

Implements `io.lettuce.core.RedisCredentialsProvider`:

- Uses `DefaultAzureCredentialBuilder().build()` to get Azure credential
- Requests token for scope `https://redis.azure.com/.default`
- Extracts `oid` from JWT payload as Redis username (same as ai-dial-core reference)
- Caches token with 120-second expiration window before refresh
- `resolveCredentials()` returns `Mono<RedisCredentials>`
- `supportsStreaming()` returns `false` (Lettuce will re-call on reconnect)
- Thread-safe via `volatile` cached token fields

Reference: `ai-dial-core/.../cache/AzureCredentialsResolver.java`

#### `RedisConfig.java` changes

```java

@Bean
public RedisConnectionFactory redisConnectionFactory(
        RedisCredentialsProviderFactory credProviderFactory) {

    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
    config.setHostName(cacheProperties.getRedis().getHost());
    config.setPort(cacheProperties.getRedis().getPort());

    var builder = LettuceClientConfiguration.builder();

    Optional<RedisCredentialsProvider> credProvider = credProviderFactory.create();
    if (credProvider.isPresent()) {
        builder.redisCredentialsProviderFactory(() -> credProvider.get());
        builder.useSsl();  // cloud Redis always requires TLS
    } else if (hasPassword()) {
        config.setPassword(cacheProperties.getRedis().getPassword());
        if (cacheProperties.getRedis().isSsl()) {
            builder.useSsl();
        }
    }

    var factory = new LettuceConnectionFactory(config, builder.build());
    factory.setValidateConnection(true);
    return factory;
}
```

**Key API note**: `LettuceClientConfiguration.builder().redisCredentialsProviderFactory()` accepts
`org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory`
(a functional interface `RedisConfiguration -> RedisCredentialsProvider`).
Confirmed available in Spring Data Redis 4.0 / Lettuce 6.8.2.

### Dependencies

```groovy
// Cloud provider Redis authentication
implementation 'com.azure:azure-identity:${azure_identity_version}'
implementation 'com.amazonaws:aws-java-sdk-core:${aws_sdk_core_version}'
implementation 'com.google.cloud:google-cloud-iamcredentials:${gcp_iamcredentials_version}'
```

Versions are managed in `gradle.properties`.

### SSL

Cloud-managed Redis requires TLS. When `provider != NONE`, SSL is force-enabled. The current `ssl` property on
`RedisProperties` already exists but is not wired — this task fixes that too.

## Scope

All three cloud providers (Azure, AWS, GCP) are implemented in a single pass:

1. `RedisAuthProvider` enum
2. `AwsRedisProperties`, `GcpRedisProperties` config data classes
3. Update `RedisProperties` with provider, aws, gcp fields
4. `AzureRedisCredentialsProvider` — Azure Managed Identity via `DefaultAzureCredential`
5. `AwsRedisCredentialsProvider` + `IamAuthTokenRequest` — AWS ElastiCache SigV4 IAM auth
6. `GcpRedisCredentialsProvider` — GCP Memorystore IAM access tokens
7. `ProxyRedisCredentialsProviderFactory` — creates the right provider based on config
8. Update `RedisConfig` — wire credentials provider + SSL
9. Update `application.yaml`
10. Add cloud SDK dependencies to `build.gradle` (versions in `gradle.properties`)
11. Unit tests for all providers and the factory

## Potential Risks

1. **Lettuce API compatibility**: The `redisCredentialsProviderFactory()` method on `LettuceClientConfiguration.Builder`
   needs verification in Spring Boot 4.0. If not available, fallback: configure Lettuce `ClientOptions` directly via
   `LettuceClientConfiguration.builder().clientOptions()`
2. **Token refresh on long-lived connections**: `supportsStreaming() = false` means Lettuce only calls
   `resolveCredentials()` on new connections. If Azure tokens expire while a connection is alive, we may need to enable
   streaming or set connection max-age. Start simple, iterate if needed.
3. **SSL enforcement**: Some local/test Redis setups don't support TLS — the force-SSL-on-cloud-provider logic handles
   this by only forcing SSL when `provider != NONE`

## Implementation Status

All files have been created/modified. API confirmed: `redisCredentialsProviderFactory()` exists in Spring Data Redis
4.0 / Lettuce 6.8.2.

### Created (8 new files)

- [x] `config/RedisAuthProvider.java` — enum
- [x] `config/AwsRedisProperties.java` — data class
- [x] `config/GcpRedisProperties.java` — data class
- [x] `credentials/AzureRedisCredentialsProvider.java` — Azure Managed Identity
- [x] `credentials/AwsRedisCredentialsProvider.java` — AWS ElastiCache SigV4
- [x] `credentials/GcpRedisCredentialsProvider.java` — GCP Memorystore IAM
- [x] `credentials/IamAuthTokenRequest.java` — AWS SigV4 token generator
- [x] `credentials/ProxyRedisCredentialsProviderFactory.java` — factory

### Modified (4 files)

- [x] `config/RedisProperties.java` — added provider, aws, gcp fields
- [x] `config/RedisConfig.java` — wired credentials provider + SSL
- [x] `application.yaml` — added provider config block
- [x] `build.gradle` — added cloud SDK dependencies

### Tests (4 new files)

- [x] `AzureRedisCredentialsProviderTest.java` — 7 tests
- [x] `AwsRedisCredentialsProviderTest.java` — 3 tests
- [x] `GcpRedisCredentialsProviderTest.java` — 4 tests
- [x] `ProxyRedisCredentialsProviderFactoryTest.java` — 4 tests

## Verification

1. **Build**: `./gradlew clean build` — passes with new dependencies
2. **Unit tests**: `./gradlew :sdmx-proxy:test` — tests for all three providers and the factory
3. **Local Redis** (manual): Set `CACHE_MODE=REDIS`, `REDIS_AUTH_PROVIDER=NONE` — verify existing password auth still
   works
4. **Azure Redis** (manual): Set `CACHE_MODE=REDIS`, `REDIS_AUTH_PROVIDER=AZURE`, deploy to Azure with Managed
   Identity — verify token-based auth works
5. **AWS ElastiCache** (manual): Set `CACHE_MODE=REDIS`, `REDIS_AUTH_PROVIDER=AWS` with IAM role — verify SigV4 auth
   works
6. **GCP Memorystore** (manual): Set `CACHE_MODE=REDIS`, `REDIS_AUTH_PROVIDER=GCP` with service account — verify IAM
   auth works
