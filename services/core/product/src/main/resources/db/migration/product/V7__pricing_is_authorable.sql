-- =============================================================================================
-- Pricing becomes something an institution can write.
--
-- `fee_rules`, `limit_rules` and `loan_rules` have been fully modelled and constrained since V2
-- and V5, and written by nothing outside the test suite. That is worse than "products are free",
-- because the limit evaluator denies by default: with no PER_TXN rule, every published product
-- refuses every transaction. An institution could publish a product and still not take a deposit.
--
-- No new columns here. What is missing is a writer, and a writer needs two guards the schema does
-- not yet provide.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- Guard 1: a published version's rules are as immutable as the version row.
--
-- `product_versions_are_immutable_once_published` protects the version row and nothing else, so a
-- published version's *price* could be edited freely by an UPDATE on fee_rules — the trigger was
-- guarding the label on the box rather than what is in it. A completed transaction has to stay
-- explicable after the configuration moves on, which is only true if the rules it was decided
-- under cannot change.
--
-- Enforced here rather than only in the service, for the reason V3 gives about publishing: a
-- control that lives only in the code path currently in front of the table is one refactor from
-- absent.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION product.reject_edit_of_published_pricing() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    affected UUID;
    version_status TEXT;
BEGIN
    affected := COALESCE(NEW.product_version_id, OLD.product_version_id);
    SELECT status INTO version_status
      FROM product.product_versions
     WHERE id = affected;

    IF version_status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'pricing for a published version is immutable: %', affected
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER fee_rules_are_immutable_once_published
    BEFORE INSERT OR UPDATE OR DELETE ON product.fee_rules
    FOR EACH ROW EXECUTE FUNCTION product.reject_edit_of_published_pricing();

CREATE TRIGGER limit_rules_are_immutable_once_published
    BEFORE INSERT OR UPDATE OR DELETE ON product.limit_rules
    FOR EACH ROW EXECUTE FUNCTION product.reject_edit_of_published_pricing();

CREATE TRIGGER loan_rules_are_immutable_once_published
    BEFORE INSERT OR UPDATE OR DELETE ON product.loan_rules
    FOR EACH ROW EXECUTE FUNCTION product.reject_edit_of_published_pricing();

-- The schema-wide grant in V2 applied to the tables existing at that moment. V5 and V6 added
-- loan_rules afterwards, so the grant is asserted again here rather than assumed — a writer that
-- can be refused by a missing privilege is a writer that works in tests and not in a deployment.
--
-- (Row-level security needs nothing: V5 enabled, forced and policied loan_rules when it created
-- the table. Checked rather than assumed — an earlier draft of this migration re-created the
-- policy and the build refused it, which is the schema doing its job.)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA product TO core_product;
