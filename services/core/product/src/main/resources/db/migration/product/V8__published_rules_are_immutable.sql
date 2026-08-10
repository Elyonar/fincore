-- A published version's *rules* are immutable, not just its header row.
--
-- V2 gave `product_versions` the trigger `product_versions_are_immutable_once_published`, and
-- the design has said ever since that "a published version is never edited" — the property the
-- money path depends on, because a transaction decided under version 3 has to stay explicable
-- once version 4 exists.
--
-- That trigger fires on `product_versions` only. `fee_rules`, `limit_rules` and `loan_rules`
-- have been writable against a published version since they were created. Nothing exercised it
-- because nothing outside the test suite wrote them; the rule-authoring endpoints make it
-- reachable, and a guarantee that holds only because no caller exists is not a guarantee.
--
-- Java refuses these writes too. This exists because that refusal is one refactor from absent,
-- and because every other immutability rule in this platform is enforced here rather than
-- there. The same reasoning V2 recorded for the version header, applied to the rows that
-- actually carry the price.
CREATE OR REPLACE FUNCTION product.reject_published_rule_write() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    subject UUID;
    live    TEXT;
BEGIN
    -- DELETE carries no NEW; INSERT carries no OLD. Both name the version they belong to.
    subject := CASE WHEN TG_OP = 'DELETE' THEN OLD.product_version_id ELSE NEW.product_version_id END;

    -- Runs as the invoker, so RLS applies and another tenant's version is simply not visible.
    -- That yields NULL, which is permitted here and then refused by the composite foreign key —
    -- the check that already owns cross-tenant referencing.
    SELECT status INTO live FROM product.product_versions WHERE id = subject;

    IF live = 'PUBLISHED' THEN
        RAISE EXCEPTION
            'product version % is published; its rules are immutable — draft a new version instead',
            subject
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

-- V7 already installed these three trigger names, bound to
-- `product.reject_edit_of_published_pricing()`. That function enforces the same rule as the one
-- above — a published version's rules may not be written — so this migration supersedes an
-- implementation rather than adding a guarantee: same names, same tables, same refusal, a body
-- that handles TG_OP explicitly instead of leaning on COALESCE.
--
-- Dropped by name first. Without this the migration fails on any database that has V7 applied
-- with `trigger "fee_rules_are_immutable_once_published" for relation "fee_rules" already
-- exists` — and because migrations run at context load, that failure is not one test but every
-- Core test at once.
DROP TRIGGER IF EXISTS fee_rules_are_immutable_once_published   ON product.fee_rules;
DROP TRIGGER IF EXISTS limit_rules_are_immutable_once_published ON product.limit_rules;
DROP TRIGGER IF EXISTS loan_rules_are_immutable_once_published  ON product.loan_rules;

CREATE TRIGGER fee_rules_are_immutable_once_published
    BEFORE INSERT OR UPDATE OR DELETE ON product.fee_rules
    FOR EACH ROW EXECUTE FUNCTION product.reject_published_rule_write();

CREATE TRIGGER limit_rules_are_immutable_once_published
    BEFORE INSERT OR UPDATE OR DELETE ON product.limit_rules
    FOR EACH ROW EXECUTE FUNCTION product.reject_published_rule_write();

CREATE TRIGGER loan_rules_are_immutable_once_published
    BEFORE INSERT OR UPDATE OR DELETE ON product.loan_rules
    FOR EACH ROW EXECUTE FUNCTION product.reject_published_rule_write();

-- The superseded function, now that nothing executes it. Left behind it would read as a second,
-- equally live rule.
DROP FUNCTION IF EXISTS product.reject_edit_of_published_pricing();

-- `one_fee_rule_per_operation` was the one uniqueness constraint in this schema that did not
-- carry the tenant, unlike `one_limit_per_tier_channel_type` beside it and every constraint in
-- `products` and `product_versions`. It was never wrong — `product_version_id` is a UUID and
-- the composite FK already ties it to one tenant — but a constraint that reads differently from
-- its siblings invites the reader to wonder which of them is the mistake.
ALTER TABLE product.fee_rules DROP CONSTRAINT one_fee_rule_per_operation;
ALTER TABLE product.fee_rules
    ADD CONSTRAINT one_fee_rule_per_operation UNIQUE (tenant_id, product_version_id, operation);
