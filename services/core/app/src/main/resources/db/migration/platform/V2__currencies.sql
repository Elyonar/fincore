-- The currencies this institution deals in, and how many decimal places each one has.
--
-- WHY THIS TABLE EXISTS, AND IT IS NOT THE DROPDOWN.
--
-- Currency was never constrained anywhere: no CHECK, no enum, no lookup, in any of the five
-- databases. The platform has always been able to hold a domiciliary account beside a local one —
-- an internal account holds one currency for life, tills are per-currency, and V2 of the product
-- schema made fees and limits per-currency too. Nothing needed a table to permit that.
--
-- What did need one is `exponent`. The portal formats every amount with a hardcoded two decimal
-- places, and that is simply wrong for a bank holding JPY (zero) or KWD and BHD (three). A ¥2,500
-- balance renders as ¥25.00 — off by a hundredfold, on a screen a teller counts cash against.
-- There is nowhere else that fact can come from: ISO 4217 assigns it, the ledger stores minor units
-- without opinion, and no amount of care in a component can infer it from the number.
--
-- Deliberately NOT an allow-list. Nothing validates a transaction's currency against this table,
-- and nothing should: the ledger already refuses a currency it has never heard of, and that is the
-- authority. A row here says "offer this in the dropdown and format it this way" — it does not say
-- "this is permitted", because two systems both believing they are the gate is how one of them
-- silently stops being one.
--
-- Tenant-scoped, because a Nigerian microfinance bank and a Kenyan one do not offer the same list.
-- Row-level security applies as it does everywhere else (ADR 0007).
-- The `platform` schema has no tenant helper of its own: its only table until now was the tenant
-- registry, which is deliberately not row-level secured so the gate can read it before a request
-- has a tenant to be scoped by. This one is ordinary tenant data and needs the same helper every
-- other schema defines.
CREATE OR REPLACE FUNCTION platform.current_tenant() RETURNS UUID
    LANGUAGE plpgsql STABLE AS $$
DECLARE
    raw TEXT := current_setting('app.tenant_id', true);
BEGIN
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;
    END IF;
    RETURN raw::UUID;
END;
$$;

CREATE TABLE platform.currencies (
    tenant_id  UUID        NOT NULL,
    code       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    -- Minor units per major unit, as ISO 4217 defines it: 2 for NGN and USD, 0 for JPY, 3 for KWD.
    -- Constrained rather than free, because a negative or absurd exponent would corrupt every
    -- amount rendered under it and there is no legitimate value outside this range.
    exponent   SMALLINT    NOT NULL DEFAULT 2 CHECK (exponent BETWEEN 0 AND 4),
    -- Withdrawn rather than deleted: an account opened in a currency the institution has stopped
    -- offering still exists and still has to render correctly.
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT currencies_pkey PRIMARY KEY (tenant_id, code),
    -- ISO 4217 is three upper-case letters. A lower-case or padded code would compare unequal to
    -- the one on an account and quietly split a balance in two.
    CONSTRAINT currencies_code_is_iso4217 CHECK (code ~ '^[A-Z]{3}$')
);

COMMENT ON TABLE platform.currencies IS
    'What this institution offers and how to render it. Not an allow-list — the ledger is the '
    'authority on what may be posted.';

ALTER TABLE platform.currencies ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.currencies FORCE ROW LEVEL SECURITY;

CREATE POLICY currencies_tenant_isolation ON platform.currencies
    USING (tenant_id = platform.current_tenant())
    WITH CHECK (tenant_id = platform.current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON platform.currencies TO core_orchestration;

-- A starting list for every tenant already provisioned, so an existing institution has a dropdown
-- rather than an empty one. The exponents are ISO 4217's, including the three that are not 2 —
-- which are the whole reason this table exists.
INSERT INTO platform.currencies (tenant_id, code, name, exponent)
SELECT t.id, c.code, c.name, c.exponent
  FROM platform.tenants t
 CROSS JOIN (VALUES
        ('NGN', 'Nigerian naira',      2),
        ('USD', 'US dollar',           2),
        ('EUR', 'Euro',                2),
        ('GBP', 'Pound sterling',      2),
        ('KES', 'Kenyan shilling',     2),
        ('GHS', 'Ghanaian cedi',       2),
        ('ZAR', 'South African rand',  2),
        ('XOF', 'West African CFA franc', 0),
        ('JPY', 'Japanese yen',        0),
        ('KWD', 'Kuwaiti dinar',       3)
   ) AS c(code, name, exponent)
ON CONFLICT (tenant_id, code) DO NOTHING;
