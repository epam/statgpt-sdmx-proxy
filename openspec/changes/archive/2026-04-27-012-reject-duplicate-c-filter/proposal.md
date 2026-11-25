# Proposal: Design: Reject repeated c[X] query parameters with HTTP 400

Migrated from `docs/designs/012-reject-duplicate-c-filter/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

Bug #38: when a client sends two `c[TIME_PERIOD]` parameters against a data endpoint (e.g. one `ge:` and one `le:`),
only the bound that arrives first in the query string is reflected in the response. Reversing the parameter order
produces the mirror symptom. No error is returned to the client.

## What Changes

Add a version-independent structural check in `QueryTranslatorImpl.extractFilters`: when any `c[X]` key in the
incoming request has more than one raw value, throw `FilterValidationException` with a message pointing the client
at the correct syntax. The existing `GlobalExceptionHandler:80-85` converts the exception into an HTTP 400 with
the standard `ErrorResponse` body — no new exception class or handler required.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
