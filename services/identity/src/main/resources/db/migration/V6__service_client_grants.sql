-- A service client's permissions, so it can act inside a tenant (ADR 0019).
--
-- The client-credentials token was `azp` and nothing else: the shape the ledger's caller
-- allowlist wants, where the only question is "is this Core". That is not enough for a service
-- that makes an ordinary tenant-scoped read — Notification asking Core which accounts a transfer
-- moved between needs a tenant and a permission, and had neither, so Core answered 401 to every
-- attempt and no notification was ever produced.
--
-- Declared, never requested. The set lives here, seeded at startup from configuration in the same
-- secrets-by-reference posture as the digest beside it, so widening what a service may do is a
-- deployment change somebody reviews rather than a parameter on a call.

ALTER TABLE auth.service_clients
    ADD COLUMN permissions TEXT[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN auth.service_clients.permissions IS
    'What a token minted for this client carries when it names a tenant (ADR 0019). Empty means '
    'the client can only hold the tenantless token the ledger allowlist expects.';
