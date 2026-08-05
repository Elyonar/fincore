package org.elyonar.fincore.ledger.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for every test that touches the database.
 *
 * <p>Real PostgreSQL, never an in-memory substitute: the locking, trigger, constraint and RLS
 * behaviour under test <em>is</em> PostgreSQL's, and a substitute would agree with us about
 * everything except the things worth testing ({@code docs/testing.md}).
 *
 * <p>The database is <em>supplied</em> rather than started by the test run:
 *
 * <ul>
 *   <li>locally — {@code docker compose up -d postgres} (host port 55432, the default below)
 *   <li>in CI — the {@code postgres} service container, injected via {@code SPRING_DATASOURCE_*}
 * </ul>
 *
 * <p>Flyway migrates it on context start, so the suite always runs against the current schema.
 *
 * <p><strong>Why not Testcontainers.</strong> It is the better default and was the first
 * approach here, but it needs the Docker <em>Engine</em> socket, and Docker Desktop only exposes
 * that at {@code /var/run/docker.sock} when "Allow the default Docker socket to be used" is
 * enabled. With it off the CLI works while every JVM client fails — a confusing failure to
 * inflict on a contributor. A supplied database has no such dependency and matches how CI
 * already runs. Worth revisiting once that setting is a documented prerequisite.
 *
 * <p>Tests isolate themselves by generating a fresh tenant UUID per class rather than by
 * truncating shared tables, which keeps repeated runs from colliding.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("database")
public abstract class LedgerPostgresTest {}
