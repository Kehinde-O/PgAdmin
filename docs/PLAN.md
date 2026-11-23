# Roadmap

Each milestone = one PR, CI green (unit tests + debug APK), no secrets in code.

## M1: Connections (Tier 1)
- [x] `ConnectionProfile` model (name, host, port, db, user, sslmode, CA/client-cert refs).
- [x] Encrypted profile store (Keystore AES-GCM; biometric-gated key). Unit-test the serialization layer.
- [x] Server list screen + add/edit dialog (pgAdmin "Register Server" style).
- [x] `ConnectionManager`: open/close, TLS config (verify-full, custom CA), read-only default, connect timeout, error mapping.

## M2: Object browser
- [ ] Catalog queries: databases, schemas, tables, views, matviews, sequences, functions, columns, indexes, constraints.
- [ ] Lazy-loading tree (Servers > Databases > Schemas > Tables/Views/Functions...), pgAdmin-like icons and context menu.
- [ ] Tests for catalog row mapping.

## M3: Query tool
- [ ] Multi-tab SQL editor, monospace, line numbers, SQL syntax highlighting.
- [ ] Run / Cancel, run selection, multiple statements, elapsed time, Messages tab with errors/notices.
- [ ] Results grid: sticky header, horizontal scroll, pagination (default 1000 rows), cell copy, CSV export via SAF.
- [ ] Query history (encrypted or opt-in).

## M4: pgAdmin look and feel (Tier 2)
- [ ] Dockable/resizable panes on wide screens; drawer on narrow.
- [ ] Properties, SQL (reverse-engineered DDL), Statistics tabs for selected object.
- [ ] View/Edit Data for tables with a primary key (commit flow, type handling).
- [ ] Autocomplete from catalog.
- [ ] Dashboard: sessions, locks, db size.

## M5: Hardening
- [ ] Biometric app lock + auto-lock timeout.
- [ ] Release build with signing via GitHub secrets (never commit keystores).
- [ ] Dependency audit; R8 verification of pgjdbc under minify.
- [ ] Optional: SSH tunnel.
