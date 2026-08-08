-- Organization module's tables (ADR 0012).
--
-- Two tables, deliberately. A unit is the tenant's operational subdivision; an assignment is the
-- record of who works where. What is *not* here matters as much as what is: no legal-entity
-- fields, no accounting-book fields, no jurisdiction fields. Collapsing those into "a unit"
-- is the mistake the ADR exists to prevent — a subsidiary is a legal entity (today: its own
-- tenant), a jurisdiction is a country pack, and the ledger stays organization-agnostic.

CREATE OR REPLACE FUNCTION organization.current_tenant() RETURNS UUID
LANGUAGE plpgsql STABLE AS $$
DECLARE
    raw TEXT := current_setting('app.tenant_id', true);
BEGIN
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;   -- no context: policies match nothing
    END IF;
    RETURN raw::UUID;
END;
$$;

CREATE TABLE organization.organizational_units (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL,
    parent_unit_id UUID,
    -- The platform's vocabulary for what a unit is operationally. A CHECK rather than free text
    -- because Product's limit rules and future reporting group by it; a new kind arrives by
    -- migration, with a design amendment saying what it means (ADR 0012).
    unit_type      TEXT        NOT NULL CHECK (unit_type IN (
                                   'BRANCH', 'REGION', 'COUNTRY_OPERATION', 'BUSINESS_LINE',
                                   'CENTRE', 'AGENT_NETWORK', 'DIGITAL_CHANNEL', 'OPERATIONS_TEAM')),
    -- The stable, human-legible handle — what tokens carry in their `units` claim and what tills
    -- reference. Lowercase-kebab by convention (`branch-01`); uniqueness is what matters here.
    code           TEXT        NOT NULL CHECK (length(code) BETWEEN 1 AND 100),
    name           TEXT        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
    created_by     TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at      TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    -- The code is the external handle, so it is unique per tenant — including closed units:
    -- a reused code would make an old till's branch ambiguous in an audit.
    CONSTRAINT organizational_units_code_unique UNIQUE (tenant_id, code),
    -- Composite, so a unit can only ever hang under its own tenant's tree.
    FOREIGN KEY (tenant_id, parent_unit_id) REFERENCES organization.organizational_units (tenant_id, id),
    CONSTRAINT closed_units_are_stamped CHECK (
        (status = 'CLOSED' AND closed_at IS NOT NULL)
        OR (status <> 'CLOSED' AND closed_at IS NULL))
);

CREATE INDEX organizational_units_by_parent
    ON organization.organizational_units (tenant_id, parent_unit_id);

-- Who is assigned where. The system of record from which identity provisioning derives the
-- `units` token claim (ADR 0012) — enforcement reads the claim, audit reads this table.
CREATE TABLE organization.unit_assignments (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    unit_id     UUID        NOT NULL,
    -- The principal exactly as tokens spell it (`user:ada.o`), so the audit trail and the token
    -- claim join without translation.
    principal   TEXT        NOT NULL,
    assigned_by TEXT        NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_by  TEXT,
    revoked_at  TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES organization.organizational_units (tenant_id, id),
    CONSTRAINT revocations_are_attributed CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL))
);

-- One live assignment per principal per unit. Partial, so history accumulates while the present
-- stays unambiguous.
CREATE UNIQUE INDEX one_live_assignment_per_principal_per_unit
    ON organization.unit_assignments (tenant_id, unit_id, principal)
    WHERE revoked_at IS NULL;

CREATE INDEX unit_assignments_by_principal
    ON organization.unit_assignments (tenant_id, principal)
    WHERE revoked_at IS NULL;

-- Row-level security: ENABLEd and FORCEd, restricted role, transaction-local tenant context —
-- the platform pattern, verbatim (ADR 0007).
ALTER TABLE organization.organizational_units ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.unit_assignments     ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization.organizational_units FORCE ROW LEVEL SECURITY;
ALTER TABLE organization.unit_assignments     FORCE ROW LEVEL SECURITY;

CREATE POLICY organizational_units_tenant_isolation ON organization.organizational_units
    USING (tenant_id = organization.current_tenant())
    WITH CHECK (tenant_id = organization.current_tenant());
CREATE POLICY unit_assignments_tenant_isolation ON organization.unit_assignments
    USING (tenant_id = organization.current_tenant())
    WITH CHECK (tenant_id = organization.current_tenant());
