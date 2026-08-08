-- Seed the first country pack's currency (PRD §3.1: Nigeria first).
--
-- Until now no migration, init script or seed inserted any currency at all, so a fresh
-- deployment failed the currencies foreign key on its very first POST /v1/accounts — the schema
-- could only be populated by the test suite or by hand, which is the same category of gap as the
-- unprovisionable tenant. Reference data a deployment cannot run without belongs in a migration.
--
-- ON CONFLICT DO NOTHING because existing environments (and every test) already insert NGN
-- themselves; idempotence here is what lets both paths coexist. Further currencies arrive with
-- their country packs, one migration each, so the currency list stays an auditable record rather
-- than a config file.
INSERT INTO currencies (code, minor_unit_exponent, display_name)
VALUES ('NGN', 2, 'Nigerian Naira')
ON CONFLICT (code) DO NOTHING;
