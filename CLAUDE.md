# PgApp

Native Android (Kotlin + Jetpack Compose) PostgreSQL client that mimics pgAdmin's UI.
Target device: Samsung Galaxy Z Fold 7 (large inner screen, aarch64), minSdk 26.
Security is the reason this app exists: treat every decision through that lens.

## Build / test
- No local toolchain on the author's phone. Builds run in GitHub Actions (`.github/workflows/build.yml`).
- Locally (cloud sandbox): `gradle :app:testDebugUnitTest :app:assembleDebug` (Gradle 8.10.2, JDK 17). No wrapper is committed; add one with `gradle wrapper --gradle-version 8.10.2` if useful.
- Package: `dev.pgapp`. Stack: AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01, pgjdbc 42.7.4.

## Security rules (non-negotiable)
- TLS on by default; default `sslmode=verify-full`. Never silently downgrade. Support custom CA and client cert.
- Saved passwords are encrypted with an Android Keystore key, gated by biometric/device credential. Never log or persist plaintext.
- `FLAG_SECURE` stays on. `allowBackup=false`. No query text, credentials or result data in logs or crash reports.
- Default to a read-only session (`SET default_transaction_read_only = on`); writes need an explicit per-connection toggle plus confirmation.
- Cleartext traffic disabled. Keep dependencies minimal.

## Architecture (target)
- `data/` : connection profiles (encrypted store), JDBC connection manager (run on `Dispatchers.IO`, cancellable via `Statement.cancel()`), catalog queries against `pg_catalog`.
- `domain/` : models (Server, Database, Schema, Table, Column, QueryResult).
- `ui/` : Compose. pgAdmin layout: left object-browser tree, right tabbed workspace (Dashboard, Properties, SQL, Statistics, Data Output, Messages), query tool with editor on top and results grid below. Wide (>=840dp) = side by side; narrow = tree in a drawer.
- Use pgAdmin's tree structure, icons style, and light/dark palettes.
- Keep logic in ViewModels / plain Kotlin classes so it is unit-testable without a device.

## Roadmap
See `docs/PLAN.md`. Work through the milestones in order, one PR per milestone.
