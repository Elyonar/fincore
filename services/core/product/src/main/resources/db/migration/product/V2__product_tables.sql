-- Product module's tables.
--
-- Product answers questions and returns decisions; it never posts. Its whole job is to be
-- reconstructible: a completed transaction has to stay explicable after the configuration moves
-- on, which is why versions are append-only and a decision records the version that produced it.

CREATE OR REPLACE FUNCTION product.current_tenant() RETURNS UUID
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

CREATE TABLE product.products (
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL,
    code       TEXT        NOT NULL,
    name       TEXT        NOT NULL,
    type       TEXT        NOT NULL CHECK (type IN ('SAVINGS', 'CURRENT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    CONSTRAINT products_code_unique UNIQUE (tenant_id, code)
);

CREATE TABLE product.product_versions (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL,
    product_id     UUID        NOT NULL,
    version        INT         NOT NULL CHECK (version > 0),
    status         TEXT        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED')),
    -- version orders history and breaks ties; effective_from decides which row is live. Both are
    -- needed: versions alone cannot express a change scheduled ahead of time, and timestamps alone
    -- cannot order two rows that share one. The same rule the Ledger uses for tenant config.
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by   TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES product.products (tenant_id, id),
    CONSTRAINT product_versions_unique UNIQUE (tenant_id, product_id, version),
    CONSTRAINT published_is_attributed CHECK (status <> 'PUBLISHED' OR published_by IS NOT NULL)
);

CREATE TABLE product.fee_rules (
    id                 UUID    NOT NULL DEFAULT gen_random_uuid(),
    tenant_id          UUID    NOT NULL,
    product_version_id UUID    NOT NULL,
    operation          TEXT    NOT NULL CHECK (operation IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    kind               TEXT    NOT NULL CHECK (kind IN ('FLAT', 'PERCENT')),
    flat_minor         BIGINT  CHECK (flat_minor IS NULL OR flat_minor >= 0),
    -- Integer basis points, never a decimal. A percentage applied to money is a money calculation,
    -- so hard rule 1 applies: 250 means 2.50%.
    basis_points       INT     CHECK (basis_points IS NULL OR basis_points >= 0),
    cap_minor          BIGINT  CHECK (cap_minor IS NULL OR cap_minor >= 0),
    currency           CHAR(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, product_version_id) REFERENCES product.product_versions (tenant_id, id),
    CONSTRAINT one_fee_rule_per_operation UNIQUE (product_version_id, operation),
    -- Each kind carries exactly the field it needs, so an evaluator can never read a null.
    CONSTRAINT fee_shape CHECK (
        (kind = 'FLAT'    AND flat_minor   IS NOT NULL AND basis_points IS NULL)
     OR (kind = 'PERCENT' AND basis_points IS NOT NULL AND flat_minor   IS NULL))
);

CREATE TABLE product.limit_rules (
    id                 UUID    NOT NULL DEFAULT gen_random_uuid(),
    tenant_id          UUID    NOT NULL,
    product_version_id UUID    NOT NULL,
    kyc_tier           TEXT    NOT NULL,
    channel            TEXT    NOT NULL,
    limit_type         TEXT    NOT NULL CHECK (limit_type IN ('PER_TXN', 'DAILY')),
    max_amount_minor   BIGINT  NOT NULL CHECK (max_amount_minor > 0),
    currency           CHAR(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, product_version_id) REFERENCES product.product_versions (tenant_id, id),
    CONSTRAINT one_limit_per_tier_channel_type
        UNIQUE (product_version_id, kyc_tier, channel, limit_type)
);

-- A published version is never edited; a change is a new version. Signed-off configuration has to
-- stay reconstructible, and a decision recorded against version 3 must mean forever what it meant
-- the day it was made.
CREATE OR REPLACE FUNCTION product.reject_published_edit() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION
            'product version % is published and immutable; publish a new version instead', OLD.version
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER product_versions_are_immutable_once_published
    BEFORE UPDATE OR DELETE ON product.product_versions
    FOR EACH ROW EXECUTE FUNCTION product.reject_published_edit();

ALTER TABLE product.products         ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.product_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.fee_rules        ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.limit_rules      ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.products         FORCE ROW LEVEL SECURITY;
ALTER TABLE product.product_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE product.fee_rules        FORCE ROW LEVEL SECURITY;
ALTER TABLE product.limit_rules      FORCE ROW LEVEL SECURITY;

CREATE POLICY products_tenant_isolation ON product.products
    USING (tenant_id = product.current_tenant()) WITH CHECK (tenant_id = product.current_tenant());
CREATE POLICY product_versions_tenant_isolation ON product.product_versions
    USING (tenant_id = product.current_tenant()) WITH CHECK (tenant_id = product.current_tenant());
CREATE POLICY fee_rules_tenant_isolation ON product.fee_rules
    USING (tenant_id = product.current_tenant()) WITH CHECK (tenant_id = product.current_tenant());
CREATE POLICY limit_rules_tenant_isolation ON product.limit_rules
    USING (tenant_id = product.current_tenant()) WITH CHECK (tenant_id = product.current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA product TO core_product;
