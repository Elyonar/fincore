-- =============================================================================================
-- product — the schema, in one migration.
--
-- Collapsed from 8 migrations (V1–V8) before the first release. Every one of them had
-- been applied to every database that exists, so nothing is lost by folding them: this file is a
-- dump of exactly the schema they produced, taken from a database they built. What goes is the
-- archaeology — a column added in V4 and widened in V7 reads here as the column it ended up being.
--
-- Legitimate only because there is nothing to preserve compatibility with yet. After the first
-- release the rule inverts and migrations become append-only for good: a deployed database cannot
-- be re-baselined without being rebuilt, and rebuilding a bank's database is not a migration.
-- =============================================================================================

--
--

--
-- Name: product; Type: SCHEMA; Schema: -; Owner: -
--

--
--

--
-- Name: product; Type: SCHEMA; Schema: -; Owner: -
--

-- Flyway creates the schema from its own `schemas` setting; kept so the file also stands
-- alone if it is ever applied by hand.
CREATE SCHEMA IF NOT EXISTS product;

--
-- Name: current_tenant(); Type: FUNCTION; Schema: product; Owner: -
--

CREATE FUNCTION product.current_tenant() RETURNS uuid
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    raw TEXT := current_setting('app.tenant_id', true);
BEGIN
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;
    END IF;
    RETURN raw::UUID;
END;
$$;

--
-- Name: reject_published_edit(); Type: FUNCTION; Schema: product; Owner: -
--

CREATE FUNCTION product.reject_published_edit() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION
            'product version % is published and immutable; publish a new version instead', OLD.version
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

--
-- Name: reject_published_rule_write(); Type: FUNCTION; Schema: product; Owner: -
--

CREATE FUNCTION product.reject_published_rule_write() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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

--
-- Name: fee_rules; Type: TABLE; Schema: product; Owner: -
--

CREATE TABLE product.fee_rules (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    product_version_id uuid NOT NULL,
    operation text NOT NULL,
    kind text NOT NULL,
    flat_minor bigint,
    basis_points integer,
    cap_minor bigint,
    currency character(3) NOT NULL,
    fee_account_id uuid,
    -- 10,000 basis points is 100%. The Java layer says this in a sentence (RATE_OUT_OF_RANGE);
    -- this is the thing that actually holds when some future surface forgets to.
    CONSTRAINT fee_rules_basis_points_check CHECK (((basis_points IS NULL) OR ((basis_points >= 0) AND (basis_points <= 10000)))),
    CONSTRAINT fee_rules_cap_minor_check CHECK (((cap_minor IS NULL) OR (cap_minor >= 0))),
    CONSTRAINT fee_rules_flat_minor_check CHECK (((flat_minor IS NULL) OR (flat_minor >= 0))),
    CONSTRAINT fee_rules_kind_check CHECK ((kind = ANY (ARRAY['FLAT'::text, 'PERCENT'::text]))),
    CONSTRAINT fee_rules_operation_check CHECK ((operation = ANY (ARRAY['DEPOSIT'::text, 'WITHDRAWAL'::text, 'TRANSFER'::text]))),
    CONSTRAINT fee_shape CHECK ((((kind = 'FLAT'::text) AND (flat_minor IS NOT NULL) AND (basis_points IS NULL)) OR ((kind = 'PERCENT'::text) AND (basis_points IS NOT NULL) AND (flat_minor IS NULL))))
);

ALTER TABLE ONLY product.fee_rules FORCE ROW LEVEL SECURITY;

--
-- Name: limit_rules; Type: TABLE; Schema: product; Owner: -
--

CREATE TABLE product.limit_rules (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    product_version_id uuid NOT NULL,
    kyc_tier text NOT NULL,
    channel text NOT NULL,
    limit_type text NOT NULL,
    max_amount_minor bigint NOT NULL,
    currency character(3) NOT NULL,
    -- Closed vocabularies, not free text: a limit rule for a tier or channel that never matches
    -- is a limit that silently never applies, and the evaluator denies by default — so a typo
    -- here would refuse every transaction for the tier its author meant to serve.
    CONSTRAINT limit_rules_channel_check CHECK ((channel = ANY (ARRAY['TELLER'::text, 'API'::text]))),
    CONSTRAINT limit_rules_kyc_tier_check CHECK ((kyc_tier = ANY (ARRAY['TIER_1'::text, 'TIER_2'::text, 'TIER_3'::text]))),
    CONSTRAINT limit_rules_limit_type_check CHECK ((limit_type = ANY (ARRAY['PER_TXN'::text, 'DAILY'::text]))),
    CONSTRAINT limit_rules_max_amount_minor_check CHECK ((max_amount_minor > 0))
);

ALTER TABLE ONLY product.limit_rules FORCE ROW LEVEL SECURITY;

--
-- Name: product_versions; Type: TABLE; Schema: product; Owner: -
--

CREATE TABLE product.product_versions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    product_id uuid NOT NULL,
    version integer NOT NULL,
    status text DEFAULT 'DRAFT'::text NOT NULL,
    effective_from timestamp with time zone DEFAULT now() NOT NULL,
    published_by text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by text NOT NULL,
    CONSTRAINT product_versions_status_check CHECK ((status = ANY (ARRAY['DRAFT'::text, 'PUBLISHED'::text]))),
    CONSTRAINT product_versions_version_check CHECK ((version > 0)),
    CONSTRAINT published_is_attributed CHECK (((status <> 'PUBLISHED'::text) OR (published_by IS NOT NULL))),
    CONSTRAINT publisher_differs_from_author CHECK (((status <> 'PUBLISHED'::text) OR (published_by <> created_by)))
);

ALTER TABLE ONLY product.product_versions FORCE ROW LEVEL SECURITY;

--
-- Name: products; Type: TABLE; Schema: product; Owner: -
--

CREATE TABLE product.products (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    type text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT products_type_check CHECK ((type = ANY (ARRAY['SAVINGS'::text, 'CURRENT'::text])))
);

ALTER TABLE ONLY product.products FORCE ROW LEVEL SECURITY;

--
-- Name: fee_rules fee_rules_pkey; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.fee_rules
    ADD CONSTRAINT fee_rules_pkey PRIMARY KEY (id);

--
-- Name: fee_rules fee_rules_tenant_id_id_key; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.fee_rules
    ADD CONSTRAINT fee_rules_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: limit_rules limit_rules_pkey; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.limit_rules
    ADD CONSTRAINT limit_rules_pkey PRIMARY KEY (id);

--
-- Name: limit_rules limit_rules_tenant_id_id_key; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.limit_rules
    ADD CONSTRAINT limit_rules_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: fee_rules one_fee_rule_per_operation; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.fee_rules
    ADD CONSTRAINT one_fee_rule_per_operation UNIQUE (tenant_id, product_version_id, operation);

--
-- Name: limit_rules one_limit_per_tier_channel_type; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.limit_rules
    ADD CONSTRAINT one_limit_per_tier_channel_type UNIQUE (product_version_id, kyc_tier, channel, limit_type);

--
-- Name: product_versions product_versions_pkey; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.product_versions
    ADD CONSTRAINT product_versions_pkey PRIMARY KEY (id);

--
-- Name: product_versions product_versions_tenant_id_id_key; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.product_versions
    ADD CONSTRAINT product_versions_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: product_versions product_versions_unique; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.product_versions
    ADD CONSTRAINT product_versions_unique UNIQUE (tenant_id, product_id, version);

--
-- Name: products products_code_unique; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.products
    ADD CONSTRAINT products_code_unique UNIQUE (tenant_id, code);

--
-- Name: products products_pkey; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);

--
-- Name: products products_tenant_id_id_key; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.products
    ADD CONSTRAINT products_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: fee_rules fee_rules_are_immutable_once_published; Type: TRIGGER; Schema: product; Owner: -
--

CREATE TRIGGER fee_rules_are_immutable_once_published BEFORE INSERT OR DELETE OR UPDATE ON product.fee_rules FOR EACH ROW EXECUTE FUNCTION product.reject_published_rule_write();

--
-- Name: limit_rules limit_rules_are_immutable_once_published; Type: TRIGGER; Schema: product; Owner: -
--

CREATE TRIGGER limit_rules_are_immutable_once_published BEFORE INSERT OR DELETE OR UPDATE ON product.limit_rules FOR EACH ROW EXECUTE FUNCTION product.reject_published_rule_write();

--
-- Name: product_versions product_versions_are_immutable_once_published; Type: TRIGGER; Schema: product; Owner: -
--

CREATE TRIGGER product_versions_are_immutable_once_published BEFORE DELETE OR UPDATE ON product.product_versions FOR EACH ROW EXECUTE FUNCTION product.reject_published_edit();

--
-- Name: fee_rules fee_rules_tenant_id_product_version_id_fkey; Type: FK CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.fee_rules
    ADD CONSTRAINT fee_rules_tenant_id_product_version_id_fkey FOREIGN KEY (tenant_id, product_version_id) REFERENCES product.product_versions(tenant_id, id);

--
-- Name: limit_rules limit_rules_tenant_id_product_version_id_fkey; Type: FK CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.limit_rules
    ADD CONSTRAINT limit_rules_tenant_id_product_version_id_fkey FOREIGN KEY (tenant_id, product_version_id) REFERENCES product.product_versions(tenant_id, id);

--
-- Name: product_versions product_versions_tenant_id_product_id_fkey; Type: FK CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.product_versions
    ADD CONSTRAINT product_versions_tenant_id_product_id_fkey FOREIGN KEY (tenant_id, product_id) REFERENCES product.products(tenant_id, id);

--
-- Name: fee_rules; Type: ROW SECURITY; Schema: product; Owner: -
--

ALTER TABLE product.fee_rules ENABLE ROW LEVEL SECURITY;

--
-- Name: fee_rules fee_rules_tenant_isolation; Type: POLICY; Schema: product; Owner: -
--

CREATE POLICY fee_rules_tenant_isolation ON product.fee_rules USING ((tenant_id = product.current_tenant())) WITH CHECK ((tenant_id = product.current_tenant()));

--
-- Name: limit_rules; Type: ROW SECURITY; Schema: product; Owner: -
--

ALTER TABLE product.limit_rules ENABLE ROW LEVEL SECURITY;

--
-- Name: limit_rules limit_rules_tenant_isolation; Type: POLICY; Schema: product; Owner: -
--

CREATE POLICY limit_rules_tenant_isolation ON product.limit_rules USING ((tenant_id = product.current_tenant())) WITH CHECK ((tenant_id = product.current_tenant()));

--
-- Name: product_versions; Type: ROW SECURITY; Schema: product; Owner: -
--

ALTER TABLE product.product_versions ENABLE ROW LEVEL SECURITY;

--
-- Name: product_versions product_versions_tenant_isolation; Type: POLICY; Schema: product; Owner: -
--

CREATE POLICY product_versions_tenant_isolation ON product.product_versions USING ((tenant_id = product.current_tenant())) WITH CHECK ((tenant_id = product.current_tenant()));

--
-- Name: products; Type: ROW SECURITY; Schema: product; Owner: -
--

ALTER TABLE product.products ENABLE ROW LEVEL SECURITY;

--
-- Name: products products_tenant_isolation; Type: POLICY; Schema: product; Owner: -
--

CREATE POLICY products_tenant_isolation ON product.products USING ((tenant_id = product.current_tenant())) WITH CHECK ((tenant_id = product.current_tenant()));

--
-- Name: SCHEMA product; Type: ACL; Schema: -; Owner: -
--

GRANT USAGE ON SCHEMA product TO core_product;

--
-- Name: TABLE fee_rules; Type: ACL; Schema: product; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE product.fee_rules TO core_product;

--
-- Name: TABLE limit_rules; Type: ACL; Schema: product; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE product.limit_rules TO core_product;

--
-- Name: TABLE product_versions; Type: ACL; Schema: product; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE product.product_versions TO core_product;

--
-- Name: TABLE products; Type: ACL; Schema: product; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE product.products TO core_product;

--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: product; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE fincore IN SCHEMA product GRANT SELECT,USAGE ON SEQUENCES TO core_product;

--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: product; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE fincore IN SCHEMA product GRANT SELECT,INSERT,DELETE,UPDATE ON TABLES TO core_product;

--
--
