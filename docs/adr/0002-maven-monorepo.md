# ADR 0002 — Maven multi-module monorepo

**Status:** Accepted · **Date:** 2026-08-03

## Context

The PRD mandates microservices with hard boundaries (database per service) but
the team is small and the codebase benefits from one home: shared ADRs, one CI
pipeline, atomic cross-cutting changes, and a single public story for the
build-in-public content flank. The repo was generated as a single-module Maven
project.

## Decision

One repository, Maven multi-module build:

- root `pom.xml` (packaging `pom`) inherits `spring-boot-starter-parent` and
  defines shared versions;
- each service is a module under `services/`, producing its own independently
  deployable Spring Boot jar/container;
- shared code lives under `libs/`, extracted only when a second consumer exists.

Monorepo ≠ monolith deploy: deployment topology stays per-service.

## Consequences

- Module boundaries are the service boundaries; Maven's dependency graph
  physically prevents cross-service imports.
- One `./mvnw verify` gates the whole platform in CI.
- If a service ever needs its own release cadence or team, extraction to its own
  repository is packaging work, not refactoring (clean boundaries preserved).
