# XingZhi Remote Config

Transactional remote configuration for XingZhi JVM and Android applications.

The module commits every snapshot in this order:

```text
decode → validate → revision check → atomic commit → return new value
```

`refresh(key)` fetches first, `import(snapshot)` accepts a complete snapshot already in hand, and `reload()` revalidates the persisted snapshot. A failed fetch, parse, signature check, business validation, or monotonic revision check never replaces the last known-good snapshot. Persisted snapshots are revalidated on load, coroutine cancellation is preserved, and the HTTP adapter bounds response size.

## Dependency

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/OpenXingZhi/remote-config")
    credentials(PasswordCredentials::class)
}

implementation("com.xingzhi:remote-config:1.2.0")
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
    revisionPolicy = MonotonicLongRevisionPolicy { config -> config.revision },
)

val local = client.load().getOrThrow()
val updated = client.refresh(deviceSerialNumber).getOrThrow()
val imported = client.import(snapshot).getOrThrow()
val reloaded = client.reload().getOrThrow()
```

The module does not depend on a configuration schema, DI framework, UI toolkit, license policy, or hosting vendor. Applications supply adapters for parsing and validation. Public trust roots should be bundled with the application or pinned independently, not downloaded from the same mutable origin as configuration.
