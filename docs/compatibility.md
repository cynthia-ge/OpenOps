# OpenOps capability ledger

OpenOps provides typed datasets, deterministic queries, private conversations and saved analysis through a persistent, tenant-isolated API.

| ID | Capability | Status | Boundary / next work |
|---|---|---|---|
| OO-001 | Canonical data and ingestion | Implemented subset | Typed CSV import/export, stable fingerprints, validation and persistence |
| OO-002 | Data query | Implemented subset | Projection, typed filtering and three aggregates; no arbitrary SQL |
| OO-003 | Conversational analysis | Partial | Deterministic question grammar with persisted results; no model orchestration |
| OO-004 | Provenance and saved outputs | Implemented subset | Immutable result snapshots survive source deletion |
| OO-005 | Permissions | Partial | Tenant datasets and private user conversations/results; no source/column ACLs or SSO |
| OO-006 | Connectors and retrieval | Missing | Live databases, documents, vector search and ingestion workers |
| OO-007 | Enterprise workflows | Missing | Reports, charts, dashboards, support desk, topics, capture and desktop apps |
| OO-008 | UI and operations | Missing | Original frontend, distributed jobs, schema migrations and cloud deployment |

Acceptance verifies every typed value, malformed imports, filtered aggregates, complete query-to-conversation-to-saved-result behavior, exact data provenance, persistence, tenant isolation and same-user read-only roles.

## Verified behavior — 13 November 2026

Typed CSV export supports portable canonical data and completes the existing ingestion boundary without external services. Live connectors (OO-006) and dashboards (OO-007) remain future work.

CSV acceptance verifies four-type round trips, column order, Unicode/quoting, empty values and datasets, full-row export beyond the query default, fingerprints, restart, permissions, malformed options and deleted sources.

Identity, storage and validation have explicit product modules. Acceptance verifies configuration snapshotting, tenant-bound SQL operations, transactional rollback on handled and unexpected failures, connection cleanup and persistent-record compatibility. Current Java measurements and reproduction: [validation](validation.md).

## Java 25 migration

The runtime, HTTP acceptance harness and build now use Java 25 / Spring Boot 4.1.2. Existing persistence and implemented API behavior are retained. Current coverage and test results supersede earlier stack-specific measurements: see [validation](validation.md). This migration does not expand vendor-compatibility claims.

## Required product experience — UX-001

**Planned, before the first browser release.** Implement a professional, responsive interface with complete light/dark themes and a visible, keyboard-accessible persistent toggle. Follow the [product experience acceptance contract](product-experience.md). Dependencies: real product UI and the applicable identity/access foundations; validate both themes through actual workflows, including narrow screens, preference persistence, storage failure and accessible contrast/focus. The current API foundation does not provide this browser capability.
