-- Contact addresses and communication consent.
--
-- Customer already held `phone`, because a teller needs one. What it did not hold is everything a
-- service that *sends* to a customer must ask before it does: a second address kind, and whether
-- this person agreed to be contacted at all. PRD §4.2 assigns consent records to Customer, and
-- this is where that stops being a sentence.
--
-- The immediate caller is Notification, which is forbidden from keeping its own copy of either:
-- events carry no PII (ADR 0008), so it asks at send time, and a second consent store would mean
-- two answers to "did this customer agree" with one of them wrong, discovered under audit.

-- Second address kind. Nullable like `phone`: an MFB customer with a phone and no email is the
-- common case, not an error, and a channel with no address for a customer is a recorded
-- suppression rather than a failure.
ALTER TABLE customer.customers ADD COLUMN email TEXT;

-- Consent, current state, one row per (customer, category, channel).
--
-- Deliberately *not* a boolean per customer. Consent is not one fact: a customer may accept
-- transaction alerts by SMS, refuse marketing entirely, and never have been asked about email. A
-- single flag collapses those into a guess, and the guess is made in the direction that sends.
--
-- `category` and `channel` are TEXT rather than CHECK-constrained enumerations. Notification owns
-- the channel registry and adds channels as data; a CHECK here would mean a Core migration every
-- time another service gained a delivery channel, which is precisely the coupling that makes
-- adding one expensive.
CREATE TABLE customer.communication_consent (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    customer_id UUID        NOT NULL,
    category    TEXT        NOT NULL,
    channel     TEXT        NOT NULL,
    -- Tri-state by absence: a row means the customer was asked and answered. No row means UNSET,
    -- and what UNSET permits is delivery policy — the sending service's decision, per category —
    -- not a fact this table should invent. Storing a default here would make an assumption look
    -- like a customer's answer.
    granted     BOOLEAN     NOT NULL,
    recorded_by TEXT        NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customers (tenant_id, id),
    CONSTRAINT one_consent_per_category_channel UNIQUE (tenant_id, customer_id, category, channel)
);

-- Every change, append-only. The same shape as customer_tier_changes and for the same reason: the
-- current row answers "may we send", and only the history answers "on what date did they agree,
-- and who recorded it" — which is the question NDPR actually asks, and the one a current-state
-- table alone cannot answer.
CREATE TABLE customer.consent_changes (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    customer_id UUID        NOT NULL,
    category    TEXT        NOT NULL,
    channel     TEXT        NOT NULL,
    from_state  TEXT        NOT NULL CHECK (from_state IN ('GRANTED', 'DENIED', 'UNSET')),
    to_state    TEXT        NOT NULL CHECK (to_state IN ('GRANTED', 'DENIED')),
    recorded_by TEXT        NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customers (tenant_id, id)
);

-- Append-only, enforced rather than intended. A consent history that can be edited is not
-- evidence, and evidence is the only reason to keep one.
CREATE OR REPLACE FUNCTION customer.consent_changes_are_append_only() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'customer.consent_changes is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER consent_changes_no_update
    BEFORE UPDATE OR DELETE ON customer.consent_changes
    FOR EACH ROW EXECUTE FUNCTION customer.consent_changes_are_append_only();

-- Row-level security, matching every other table in this schema.
ALTER TABLE customer.communication_consent ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer.communication_consent FORCE ROW LEVEL SECURITY;
CREATE POLICY communication_consent_tenant_isolation ON customer.communication_consent
    USING (tenant_id = customer.current_tenant())
    WITH CHECK (tenant_id = customer.current_tenant());

ALTER TABLE customer.consent_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer.consent_changes FORCE ROW LEVEL SECURITY;
CREATE POLICY consent_changes_tenant_isolation ON customer.consent_changes
    USING (tenant_id = customer.current_tenant())
    WITH CHECK (tenant_id = customer.current_tenant());

GRANT SELECT, INSERT, UPDATE ON customer.communication_consent TO core_customer;
GRANT SELECT, INSERT ON customer.consent_changes TO core_customer;

-- The lookup Notification makes on every send is account → customer, which is the opposite
-- direction from every existing query on this table. Without it the send path is a scan.
CREATE INDEX customer_accounts_by_account
    ON customer.customer_accounts (tenant_id, ledger_account_id)
    WHERE unlinked_at IS NULL;
