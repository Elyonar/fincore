# ADR 0001 — Java 25 LTS + Spring Boot for core services

**Status:** Accepted · **Date:** 2026-08-03

## Context

The PRD's stack rule is "monoglot core, earned-exception edges": one language for
all services holding domain logic, exceptions only when a concrete advantage is
recorded in an ADR. The platform targets bank due-diligence review, where the JVM
lineage of incumbent cores (Finacle, Temenos, Mambu) is the credibility baseline.
The generated starter project targeted Java 26 (non-LTS).

## Decision

Java **25 LTS** and Spring Boot for every service holding domain logic. Non-LTS
Java releases are not used for anything a bank will run. The Boot version tracks
the current stable release train via the parent pom.

## Consequences

- Enterprise credibility and the deepest hiring pool (Lagos included).
- One toolchain: shared libraries, auth glue, and review standards written once.
- Go/Python/Rust remain possible at the edges, but each requires its own ADR
  demonstrating a concrete advantage before use.
