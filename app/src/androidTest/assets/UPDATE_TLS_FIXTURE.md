`update-tls-fixture.p12` is an intentionally public test-only TLS server identity,
password `fixture-only`, with localhost/127.0.0.1 SANs. It is not an APK publisher
key and is not trusted by production code. Instrumentation verifies that default
platform trust rejects it, then injects a fixture-scoped SSL socket factory into
the internal transport test. This asset belongs only to the test APK, never the
main/debug/release application. No production trust configuration is changed.
