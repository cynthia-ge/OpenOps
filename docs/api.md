# OpenOps API

API under `/api/v1`, with local bearer auth and tenant-isolated storage.

| Method | Route | Behavior |
|---|---|---|
| GET/POST | /datasets | List metadata or ingest `{name, columns, csv}` |
| GET/DELETE | /datasets/{id} | Read immutable data or delete it |
| POST | /datasets/{id}/query | Evaluate a bounded structured query |
| GET/POST | /conversations | List own conversations or create `{dataset_id, title}` |
| GET/POST | /conversations/{id}/messages | Read history or ask `{question}` |
| GET/POST | /saved-results | List own results or save `{name, message_id}` |
| GET/DELETE | /saved-results/{id} | Read/delete own snapshot |

CSV accepts 1–50 columns and up to 5000 rows. `columns` is an ordered JSON object from lowercase identifiers to string, integer, number or boolean. The CSV header must exactly match that order. Boolean values are literal true/false; integer values have at most 15 digits; numeric values must be finite and bounded. Ragged or malformed CSV fails atomically. Imports preserve typed values and record a SHA-256 fingerprint of the canonical schema and rows.

Queries accept `select` (unique column list), `limit` (1–1000; default 100), and a single `filter: {column, op, value}` with eq/gt/lt/contains. Operators and values must match column types. `aggregate: {op, column?}` supports count, sum and avg; aggregates cannot combine with select/limit. Count counts rows; sum/avg require numeric columns. Average over no rows returns null. Responses report matched_rows and truncation or aggregate value. Every result includes dataset_id, dataset_sha256, query and creation time.

Conversation questions use an explicit grammar: `count rows`, `show rows`, `sum COLUMN`, `average COLUMN`. This is deterministic language handling, not an LLM. Unsupported questions fail without adding history. Saved results retain the original result and fingerprint after a dataset is deleted; new queries against that deleted dataset fail.

Datasets are shared within a tenant. Readers can query; only editors/admins mutate. Conversations and saved results are private to the local user ID, including from other tenant admins. No arbitrary SQL, connectors, model credentials or network data fetching are implemented.

`GET /api/v1/datasets/{id}/export.csv` downloads all stored rows as UTF-8 CSV, including datasets larger than the query's default page. Header order follows the declared schema; booleans use lowercase true/false; quoting preserves commas, quotes, newlines and Unicode. The attachment filename is dataset.csv; X-Dataset-SHA256 identifies the typed source. Reimport with the original columns schema preserves typed values and fingerprint, not original source formatting. All authenticated tenant roles may read; foreign/deleted datasets return 404. Query parameters are rejected. CSV cells preserve raw source values; this is data interchange, not a spreadsheet formula sanitizer.

## AI product direction

Requirement added 2026-11-16: OpenOps must become fully AI enabled, agentic and conversational across its core journeys. The [AI capability and acceptance guide](ai-capabilities.md) defines the required work and evidence. This direction does not change the implementation scope or historical validation results recorded above.
