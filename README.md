# XingZhi Remote Config

Transactional remote configuration for XingZhi JVM and Android applications.

The module guarantees the refresh order:

```text
fetch complete snapshot → decode → validate → atomic commit → return new value
```

A failed fetch, parse, signature check, or business validation never replaces the last known-good snapshot.

## Dependency

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/OpenXingZhi/remote-config")
    credentials(PasswordCredentials::class)
}

implementation("com.xingzhi:remote-config:1.0.0")
```

## Interface

```kotlin
val client = RemoteConfigClient(
    source = HttpConfigSource(
        contentUri = { key -> URI("https://example.com/config/$key.yml") },
        signatureUri = { key -> URI("https://example.com/config/$key.yml.asc") },
    ),
    store = FileConfigStore(context.filesDir.toPath()),
    decoder = ConfigDecoder(::decodeYaml),
    validator = ConfigValidator { snapshot, config ->
        signatureVerifier.verify(
            content = snapshot.content,
            signature = requireNotNull(snapshot.signature),
            publicKey = trustedPublicKey,
        ).getOrThrow()
        validateApplicationPolicy(config)
    },
)

val local = client.load().getOrThrow()
val updated = client.refresh(deviceSerialNumber).getOrThrow()
```

The module does not depend on a configuration schema, DI framework, UI toolkit, license policy, or hosting vendor. Applications supply adapters for parsing and validation. Public trust roots should be bundled with the application or pinned independently, not downloaded from the same mutable origin as configuration.
