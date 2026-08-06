-- Customer's administrative surface.
--
-- V2 gave Customer the tables Orchestration reads inside Phase A. It gave nobody a way to put a
-- customer *into* those tables: until now every customer on this platform was created by a test
-- reaching into the schema with raw SQL, which is a workaround masquerading as a fixture. This
-- migration adds what an administrative API needs and nothing else.

-- Who created the record. Attribution is not optional on a table holding PII — "which of our staff
-- entered this person's details" is a question a regulator asks, and an answer reconstructed from
-- application logs is not an answer.
ALTER TABLE customer.customers ADD COLUMN created_by TEXT;

-- Every KYC tier change, append-only.
--
-- The tier on `customers` is the current value; this is how it got there. A tier is the ceiling on
-- what someone may move, so a silent change to it is a silent change to a limit — the audit trail
-- is the control, not a convenience. Kept in Customer rather than a platform-wide audit log because
-- it is PII-adjacent and belongs on this side of the boundary (ADR 0006).
CREATE TABLE customer.customer_tier_changes (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    customer_id UUID        NOT NULL,
    from_tier   TEXT        NOT NULL,
    to_tier     TEXT        NOT NULL,
    reason      TEXT        NOT NULL,
    changed_by  TEXT        NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customers (tenant_id, id),
    -- A change that changes nothing is a caller bug, and recording it would pad the trail with
    -- entries that look like activity.
    CONSTRAINT tier_actually_changed CHECK (from_tier <> to_tier)
);

CREATE INDEX customer_tier_changes_by_customer
    ON customer.customer_tier_changes (tenant_id, customer_id, changed_at DESC);

-- Append-only, enforced where it cannot be forgotten. The same argument as the Ledger's anchors:
-- an audit trail that application code is trusted not to rewrite is an audit trail with a
-- deployment away from being wrong.
CREATE OR REPLACE FUNCTION customer.reject_tier_history_edit() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'customer.customer_tier_changes is append-only'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER customer_tier_changes_are_append_only
    BEFORE UPDATE OR DELETE ON customer.customer_tier_changes
    FOR EACH ROW EXECUTE FUNCTION customer.reject_tier_history_edit();

ALTER TABLE customer.customer_tier_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer.customer_tier_changes FORCE ROW LEVEL SECURITY;

CREATE POLICY customer_tier_changes_tenant_isolation ON customer.customer_tier_changes
    USING (tenant_id = customer.current_tenant())
    WITH CHECK (tenant_id = customer.current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA customer TO core_customer;
