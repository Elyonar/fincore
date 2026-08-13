-- Two CHECK constraints that made a tenant's vocabulary the platform's, removed.
--
-- ---------------------------------------------------------------------------
-- 1. KYC tier
-- ---------------------------------------------------------------------------
-- `limit_rules.kyc_tier` named exactly TIER_1, TIER_2 and TIER_3. Meanwhile
-- `customer.customers.kyc_tier` was, and always had been, free text.
--
-- So the two halves of one idea disagreed, and the permissive half was the wrong one: an
-- institution could assign somebody TIER_4 and then discover it could not write the limit that
-- governs what that person may move. The tier existed on the customer and could not exist on the
-- rule. A limit that cannot be written is not a loose limit — it is no limit, because the evaluator
-- denies by default and every transaction under that tier was refused.
--
-- Tiers are a regulator's vocabulary. Nigeria defines three; Kenya, Ghana and the CFA zone define
-- their own, differently named and differently counted. The list now lives in
-- `customer.kyc_tiers`, which is the service that owns the customer's tier.
--
-- NOT replaced by a foreign key, and not only because it is in another database now. A rule
-- referencing a tier the institution later retires must keep working — retiring a tier stops it
-- being assigned; it does not invalidate the pricing of the people still on it.
ALTER TABLE product.limit_rules DROP CONSTRAINT IF EXISTS limit_rules_kyc_tier_check;

-- A shape check stays. Dropping the enumeration should widen the vocabulary, not admit an empty
-- string or a sentence — a rule whose tier is untypeable matches nobody and is silently dead.
ALTER TABLE product.limit_rules
    ADD CONSTRAINT limit_rules_kyc_tier_is_a_handle CHECK (kyc_tier ~ '^[A-Z0-9_]{2,32}$');

-- ---------------------------------------------------------------------------
-- 2. Product type
-- ---------------------------------------------------------------------------
-- `products.type` named SAVINGS and CURRENT. A fixed deposit, a target-savings product, a group
-- account — each is an ordinary product this platform can already price and hold, and each needed
-- a migration and a release to name. That is a deployment to add a word.
--
-- The type is descriptive here: nothing in the evaluator branches on it. It selects an icon, groups
-- a list, and tells a customer what they are opening. Exactly the kind of value that belongs to the
-- institution rather than to the code.
ALTER TABLE product.products DROP CONSTRAINT IF EXISTS products_type_check;

ALTER TABLE product.products
    ADD CONSTRAINT products_type_is_a_handle CHECK (type ~ '^[A-Z0-9_]{2,32}$');

-- The types this institution offers, so the field is a choice rather than free text.
CREATE TABLE product.product_types (
    tenant_id  UUID        NOT NULL,
    code       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT product_types_pkey PRIMARY KEY (tenant_id, code),
    CONSTRAINT product_types_code_is_a_handle CHECK (code ~ '^[A-Z0-9_]{2,32}$')
);

COMMENT ON TABLE product.product_types IS
    'What kinds of product this institution offers. Descriptive — nothing prices differently by it.';

ALTER TABLE product.product_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.product_types FORCE ROW LEVEL SECURITY;

CREATE POLICY product_types_tenant_isolation ON product.product_types
    USING (tenant_id = product.current_tenant())
    WITH CHECK (tenant_id = product.current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON product.product_types TO product_app;

-- The two the platform assumed, plus the ones institutions ask for first. Seeded per tenant so an
-- existing catalogue keeps working and a new one has somewhere to start.
INSERT INTO product.product_types (tenant_id, code, name)
SELECT t.id, v.code, v.name
  FROM product.tenants t
 CROSS JOIN (VALUES
        ('SAVINGS',       'Savings'),
        ('CURRENT',       'Current'),
        ('FIXED_DEPOSIT', 'Fixed deposit'),
        ('TARGET',        'Target savings')
   ) AS v(code, name)
ON CONFLICT (tenant_id, code) DO NOTHING;
