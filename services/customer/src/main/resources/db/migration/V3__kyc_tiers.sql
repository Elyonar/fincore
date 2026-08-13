-- The KYC tiers this institution recognises.
--
-- WHY THIS IS TENANT DATA AND NOT A CONSTANT.
--
-- A tier is a regulator's idea, not a platform's. Nigeria's CBN defines three with specific
-- documentary requirements and transaction ceilings; Kenya, Ghana and the CFA zone each define
-- their own, with different names and a different number of them. Baking `TIER_1..3` into the code
-- makes the platform wrong for every institution outside one jurisdiction, and there is no version
-- of that list that is right everywhere.
--
-- AND THERE WAS ALREADY A SPLIT.
--
-- `customer.customers.kyc_tier` has always been free text with a default. `product.limit_rules`
-- carried a CHECK naming exactly three values. So an institution could put a customer on `TIER_4`
-- and then be unable to write a limit for it — the tier existed on the person and could not exist
-- on the rule that governs what they may move. The two halves of one idea disagreed, and the
-- customer half was the permissive one, which is the worse way round: the tier that could not be
-- priced was the one that had already been assigned to somebody.
--
-- This table is the tier's home. The matching migration on the product side removes that CHECK, so
-- a tier an institution defines can actually be priced.
--
-- Deliberately NOT enforced against `customers.kyc_tier`. A foreign key would refuse to let an
-- institution retire a tier while anybody still holds it — which is exactly backwards: retiring a
-- tier is how you stop assigning it, and the people already on it keep it until they are reviewed.
CREATE TABLE customer.kyc_tiers (
    tenant_id  UUID        NOT NULL,
    code       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    -- What the institution must hold before it may assign this tier. Prose, because it is a
    -- regulator's sentence and nothing here evaluates it — it is shown to whoever is deciding.
    requires   TEXT,
    -- The order they escalate in, so a screen lists them as a ladder rather than alphabetically.
    rank       SMALLINT    NOT NULL DEFAULT 0,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT kyc_tiers_pkey PRIMARY KEY (tenant_id, code),
    CONSTRAINT kyc_tiers_code_is_a_handle CHECK (code ~ '^[A-Z0-9_]{2,32}$')
);

COMMENT ON TABLE customer.kyc_tiers IS
    'The tiers this institution recognises. A regulator''s vocabulary, not the platform''s.';

ALTER TABLE customer.kyc_tiers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer.kyc_tiers FORCE ROW LEVEL SECURITY;

CREATE POLICY kyc_tiers_tenant_isolation ON customer.kyc_tiers
    USING (tenant_id = customer.current_tenant())
    WITH CHECK (tenant_id = customer.current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON customer.kyc_tiers TO customer_app;

-- The three the platform assumed, seeded for every tenant already provisioned so nothing changes
-- underneath an existing institution. They are now a starting position rather than the law.
INSERT INTO customer.kyc_tiers (tenant_id, code, name, requires, rank)
SELECT t.id, v.code, v.name, v.requires, v.rank
  FROM customer.tenants t
 CROSS JOIN (VALUES
        ('TIER_1', 'Tier 1', 'A name and a phone number. Lowest ceilings.', 1),
        ('TIER_2', 'Tier 2', 'Identity verified against a document.',       2),
        ('TIER_3', 'Tier 3', 'Identity and address verified. Highest ceilings.', 3)
   ) AS v(code, name, requires, rank)
ON CONFLICT (tenant_id, code) DO NOTHING;
