-- Multi-factor authentication: TOTP enrolments and one-time recovery codes (ADR 0018 phase 2).
--
-- The TOTP secret is the sensitive material here. It is stored encrypted at rest (AES-GCM, keyed
-- by deployment reference), never in plaintext — the same posture Notification takes for recipient
-- addresses. Recovery codes are stored only as digests, like refresh tokens: a stored value that
-- reconstructs a factor is a factor sitting in the database.

CREATE TABLE identity.mfa_enrollments (
    tenant_id        UUID        NOT NULL REFERENCES identity.tenants (id),
    user_id          UUID        NOT NULL,
    method           TEXT        NOT NULL CHECK (method IN ('TOTP')),
    secret_encrypted TEXT        NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at     TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, user_id, method),
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity.users (tenant_id, id)
);
ALTER TABLE identity.mfa_enrollments ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.mfa_enrollments FORCE ROW LEVEL SECURITY;
CREATE POLICY mfa_enrollments_tenant ON identity.mfa_enrollments
    USING (tenant_id = identity.current_tenant());
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.mfa_enrollments TO identity_app;

CREATE TABLE identity.mfa_recovery_codes (
    tenant_id   UUID        NOT NULL REFERENCES identity.tenants (id),
    user_id     UUID        NOT NULL,
    code_digest TEXT        NOT NULL,
    used_at     TIMESTAMPTZ,
    PRIMARY KEY (code_digest),
    FOREIGN KEY (tenant_id, user_id) REFERENCES identity.users (tenant_id, id)
);
CREATE INDEX mfa_recovery_by_user ON identity.mfa_recovery_codes (tenant_id, user_id);
ALTER TABLE identity.mfa_recovery_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.mfa_recovery_codes FORCE ROW LEVEL SECURITY;
CREATE POLICY mfa_recovery_tenant ON identity.mfa_recovery_codes
    USING (tenant_id = identity.current_tenant());
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.mfa_recovery_codes TO identity_app;
