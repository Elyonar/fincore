-- =============================================================================================
-- customer — the schema, in one migration.
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
-- Name: customer; Type: SCHEMA; Schema: -; Owner: -
--

--
--

--
-- Name: customer; Type: SCHEMA; Schema: -; Owner: -
--

-- Flyway creates the schema from its own `schemas` setting; kept so the file also stands
-- alone if it is ever applied by hand.
CREATE SCHEMA IF NOT EXISTS customer;

--
-- Name: consent_changes_are_append_only(); Type: FUNCTION; Schema: customer; Owner: -
--

CREATE FUNCTION customer.consent_changes_are_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'customer.consent_changes is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

--
-- Name: current_tenant(); Type: FUNCTION; Schema: customer; Owner: -
--

CREATE FUNCTION customer.current_tenant() RETURNS uuid
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    raw TEXT := current_setting('app.tenant_id', true);
BEGIN
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;   -- no context: policies match nothing
    END IF;
    RETURN raw::UUID;
END;
$$;

--
-- Name: reject_tier_history_edit(); Type: FUNCTION; Schema: customer; Owner: -
--

CREATE FUNCTION customer.reject_tier_history_edit() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'customer.customer_tier_changes is append-only'
        USING ERRCODE = 'restrict_violation';
END;
$$;

--
-- Name: communication_consent; Type: TABLE; Schema: customer; Owner: -
--

CREATE TABLE customer.communication_consent (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    category text NOT NULL,
    channel text NOT NULL,
    granted boolean NOT NULL,
    recorded_by text NOT NULL,
    recorded_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY customer.communication_consent FORCE ROW LEVEL SECURITY;

--
-- Name: consent_changes; Type: TABLE; Schema: customer; Owner: -
--

CREATE TABLE customer.consent_changes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    category text NOT NULL,
    channel text NOT NULL,
    from_state text NOT NULL,
    to_state text NOT NULL,
    recorded_by text NOT NULL,
    recorded_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT consent_changes_from_state_check CHECK ((from_state = ANY (ARRAY['GRANTED'::text, 'DENIED'::text, 'UNSET'::text]))),
    CONSTRAINT consent_changes_to_state_check CHECK ((to_state = ANY (ARRAY['GRANTED'::text, 'DENIED'::text])))
);

ALTER TABLE ONLY customer.consent_changes FORCE ROW LEVEL SECURITY;

--
-- Name: customer_accounts; Type: TABLE; Schema: customer; Owner: -
--

CREATE TABLE customer.customer_accounts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    ledger_account_id uuid NOT NULL,
    currency character(3) NOT NULL,
    role text DEFAULT 'PRIMARY'::text NOT NULL,
    linked_at timestamp with time zone DEFAULT now() NOT NULL,
    unlinked_at timestamp with time zone,
    account_number text,
    product_code text
);

ALTER TABLE ONLY customer.customer_accounts FORCE ROW LEVEL SECURITY;

--
-- Name: COLUMN customer_accounts.product_code; Type: COMMENT; Schema: customer; Owner: -
--

COMMENT ON COLUMN customer.customer_accounts.product_code IS 'The product this account was opened under. Decides which fee and limit rules a transaction on it is evaluated against. Null only for accounts linked before the column existed; the money path refuses those rather than guessing.';

--
-- Name: customer_tier_changes; Type: TABLE; Schema: customer; Owner: -
--

CREATE TABLE customer.customer_tier_changes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    from_tier text NOT NULL,
    to_tier text NOT NULL,
    reason text NOT NULL,
    changed_by text NOT NULL,
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT tier_actually_changed CHECK ((from_tier <> to_tier))
);

ALTER TABLE ONLY customer.customer_tier_changes FORCE ROW LEVEL SECURITY;

--
-- Name: customers; Type: TABLE; Schema: customer; Owner: -
--

CREATE TABLE customer.customers (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    external_ref text NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    kyc_tier text DEFAULT 'TIER_1'::text NOT NULL,
    full_name text NOT NULL,
    phone text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by text,
    email text,
    locale text,
    CONSTRAINT customers_status_check CHECK ((status = ANY (ARRAY['PROSPECT'::text, 'ACTIVE'::text, 'DORMANT'::text, 'CLOSED'::text])))
);

ALTER TABLE ONLY customer.customers FORCE ROW LEVEL SECURITY;

--
-- Name: COLUMN customers.locale; Type: COMMENT; Schema: customer; Owner: -
--

COMMENT ON COLUMN customer.customers.locale IS 'BCP 47 language tag, e.g. en, ha, yo, ig. NULL means never asked: the sending service falls back to the tenant default rather than assuming English.';

--
-- Name: numbering; Type: TABLE; Schema: customer; Owner: -
--

CREATE TABLE customer.numbering (
    tenant_id uuid NOT NULL,
    series text NOT NULL,
    prefix text DEFAULT ''::text NOT NULL,
    width integer DEFAULT 10 NOT NULL,
    next_value bigint DEFAULT 1 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by text NOT NULL,
    CONSTRAINT numbering_next_value_check CHECK ((next_value >= 1)),
    CONSTRAINT numbering_series_check CHECK ((series = ANY (ARRAY['CUSTOMER'::text, 'ACCOUNT'::text]))),
    CONSTRAINT numbering_width_check CHECK (((width >= 1) AND (width <= 20)))
);

ALTER TABLE ONLY customer.numbering FORCE ROW LEVEL SECURITY;

--
-- Name: communication_consent communication_consent_pkey; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.communication_consent
    ADD CONSTRAINT communication_consent_pkey PRIMARY KEY (id);

--
-- Name: communication_consent communication_consent_tenant_id_id_key; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.communication_consent
    ADD CONSTRAINT communication_consent_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: consent_changes consent_changes_pkey; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.consent_changes
    ADD CONSTRAINT consent_changes_pkey PRIMARY KEY (id);

--
-- Name: consent_changes consent_changes_tenant_id_id_key; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.consent_changes
    ADD CONSTRAINT consent_changes_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: customer_accounts customer_accounts_pkey; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.customer_accounts
    ADD CONSTRAINT customer_accounts_pkey PRIMARY KEY (id);

--
-- Name: customer_accounts customer_accounts_tenant_id_id_key; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.customer_accounts
    ADD CONSTRAINT customer_accounts_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: customer_tier_changes customer_tier_changes_pkey; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.customer_tier_changes
    ADD CONSTRAINT customer_tier_changes_pkey PRIMARY KEY (id);

--
-- Name: customer_tier_changes customer_tier_changes_tenant_id_id_key; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.customer_tier_changes
    ADD CONSTRAINT customer_tier_changes_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: customers customers_external_ref_unique; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.customers
    ADD CONSTRAINT customers_external_ref_unique UNIQUE (tenant_id, external_ref);

--
-- Name: customers customers_pkey; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.customers
    ADD CONSTRAINT customers_pkey PRIMARY KEY (id);

--
-- Name: customers customers_tenant_id_id_key; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.customers
    ADD CONSTRAINT customers_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: numbering numbering_pkey; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.numbering
    ADD CONSTRAINT numbering_pkey PRIMARY KEY (tenant_id, series);

--
-- Name: communication_consent one_consent_per_category_channel; Type: CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.communication_consent
    ADD CONSTRAINT one_consent_per_category_channel UNIQUE (tenant_id, customer_id, category, channel);

--
-- Name: customer_accounts_by_account; Type: INDEX; Schema: customer; Owner: -
--

CREATE INDEX customer_accounts_by_account ON customer.customer_accounts USING btree (tenant_id, ledger_account_id) WHERE (unlinked_at IS NULL);

--
-- Name: customer_accounts_by_customer; Type: INDEX; Schema: customer; Owner: -
--

CREATE INDEX customer_accounts_by_customer ON customer.customer_accounts USING btree (tenant_id, customer_id) WHERE (unlinked_at IS NULL);

--
-- Name: customer_accounts_by_number; Type: INDEX; Schema: customer; Owner: -
--

CREATE INDEX customer_accounts_by_number ON customer.customer_accounts USING btree (tenant_id, account_number) WHERE (account_number IS NOT NULL);

--
-- Name: customer_accounts_live_by_account; Type: INDEX; Schema: customer; Owner: -
--

CREATE INDEX customer_accounts_live_by_account ON customer.customer_accounts USING btree (tenant_id, ledger_account_id) WHERE (unlinked_at IS NULL);

--
-- Name: customer_accounts_number_per_tenant; Type: INDEX; Schema: customer; Owner: -
--

CREATE UNIQUE INDEX customer_accounts_number_per_tenant ON customer.customer_accounts USING btree (tenant_id, account_number) WHERE ((account_number IS NOT NULL) AND (unlinked_at IS NULL));

--
-- Name: customer_tier_changes_by_customer; Type: INDEX; Schema: customer; Owner: -
--

CREATE INDEX customer_tier_changes_by_customer ON customer.customer_tier_changes USING btree (tenant_id, customer_id, changed_at DESC);

--
-- Name: one_live_holder_per_account; Type: INDEX; Schema: customer; Owner: -
--

CREATE UNIQUE INDEX one_live_holder_per_account ON customer.customer_accounts USING btree (tenant_id, ledger_account_id) WHERE (unlinked_at IS NULL);

--
-- Name: consent_changes consent_changes_no_update; Type: TRIGGER; Schema: customer; Owner: -
--

CREATE TRIGGER consent_changes_no_update BEFORE DELETE OR UPDATE ON customer.consent_changes FOR EACH ROW EXECUTE FUNCTION customer.consent_changes_are_append_only();

--
-- Name: customer_tier_changes customer_tier_changes_are_append_only; Type: TRIGGER; Schema: customer; Owner: -
--

CREATE TRIGGER customer_tier_changes_are_append_only BEFORE DELETE OR UPDATE ON customer.customer_tier_changes FOR EACH ROW EXECUTE FUNCTION customer.reject_tier_history_edit();

--
-- Name: communication_consent communication_consent_tenant_id_customer_id_fkey; Type: FK CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.communication_consent
    ADD CONSTRAINT communication_consent_tenant_id_customer_id_fkey FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customers(tenant_id, id);

--
-- Name: consent_changes consent_changes_tenant_id_customer_id_fkey; Type: FK CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.consent_changes
    ADD CONSTRAINT consent_changes_tenant_id_customer_id_fkey FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customers(tenant_id, id);

--
-- Name: customer_accounts customer_accounts_tenant_id_customer_id_fkey; Type: FK CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.customer_accounts
    ADD CONSTRAINT customer_accounts_tenant_id_customer_id_fkey FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customers(tenant_id, id);

--
-- Name: customer_tier_changes customer_tier_changes_tenant_id_customer_id_fkey; Type: FK CONSTRAINT; Schema: customer; Owner: -
--

ALTER TABLE ONLY customer.customer_tier_changes
    ADD CONSTRAINT customer_tier_changes_tenant_id_customer_id_fkey FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customers(tenant_id, id);

--
-- Name: communication_consent; Type: ROW SECURITY; Schema: customer; Owner: -
--

ALTER TABLE customer.communication_consent ENABLE ROW LEVEL SECURITY;

--
-- Name: communication_consent communication_consent_tenant_isolation; Type: POLICY; Schema: customer; Owner: -
--

CREATE POLICY communication_consent_tenant_isolation ON customer.communication_consent USING ((tenant_id = customer.current_tenant())) WITH CHECK ((tenant_id = customer.current_tenant()));

--
-- Name: consent_changes; Type: ROW SECURITY; Schema: customer; Owner: -
--

ALTER TABLE customer.consent_changes ENABLE ROW LEVEL SECURITY;

--
-- Name: consent_changes consent_changes_tenant_isolation; Type: POLICY; Schema: customer; Owner: -
--

CREATE POLICY consent_changes_tenant_isolation ON customer.consent_changes USING ((tenant_id = customer.current_tenant())) WITH CHECK ((tenant_id = customer.current_tenant()));

--
-- Name: customer_accounts; Type: ROW SECURITY; Schema: customer; Owner: -
--

ALTER TABLE customer.customer_accounts ENABLE ROW LEVEL SECURITY;

--
-- Name: customer_accounts customer_accounts_tenant_isolation; Type: POLICY; Schema: customer; Owner: -
--

CREATE POLICY customer_accounts_tenant_isolation ON customer.customer_accounts USING ((tenant_id = customer.current_tenant())) WITH CHECK ((tenant_id = customer.current_tenant()));

--
-- Name: customer_tier_changes; Type: ROW SECURITY; Schema: customer; Owner: -
--

ALTER TABLE customer.customer_tier_changes ENABLE ROW LEVEL SECURITY;

--
-- Name: customer_tier_changes customer_tier_changes_tenant_isolation; Type: POLICY; Schema: customer; Owner: -
--

CREATE POLICY customer_tier_changes_tenant_isolation ON customer.customer_tier_changes USING ((tenant_id = customer.current_tenant())) WITH CHECK ((tenant_id = customer.current_tenant()));

--
-- Name: customers; Type: ROW SECURITY; Schema: customer; Owner: -
--

ALTER TABLE customer.customers ENABLE ROW LEVEL SECURITY;

--
-- Name: customers customers_tenant_isolation; Type: POLICY; Schema: customer; Owner: -
--

CREATE POLICY customers_tenant_isolation ON customer.customers USING ((tenant_id = customer.current_tenant())) WITH CHECK ((tenant_id = customer.current_tenant()));

--
-- Name: numbering; Type: ROW SECURITY; Schema: customer; Owner: -
--

ALTER TABLE customer.numbering ENABLE ROW LEVEL SECURITY;

--
-- Name: numbering numbering_tenant_isolation; Type: POLICY; Schema: customer; Owner: -
--

CREATE POLICY numbering_tenant_isolation ON customer.numbering USING ((tenant_id = customer.current_tenant())) WITH CHECK ((tenant_id = customer.current_tenant()));

--
-- Name: SCHEMA customer; Type: ACL; Schema: -; Owner: -
--

GRANT USAGE ON SCHEMA customer TO core_customer;

--
-- Name: TABLE communication_consent; Type: ACL; Schema: customer; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE customer.communication_consent TO core_customer;

--
-- Name: TABLE consent_changes; Type: ACL; Schema: customer; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE customer.consent_changes TO core_customer;

--
-- Name: TABLE customer_accounts; Type: ACL; Schema: customer; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE customer.customer_accounts TO core_customer;

--
-- Name: TABLE customer_tier_changes; Type: ACL; Schema: customer; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE customer.customer_tier_changes TO core_customer;

--
-- Name: TABLE customers; Type: ACL; Schema: customer; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE customer.customers TO core_customer;

--
-- Name: TABLE numbering; Type: ACL; Schema: customer; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE customer.numbering TO core_customer;

--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: customer; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE fincore IN SCHEMA customer GRANT SELECT,USAGE ON SEQUENCES TO core_customer;

--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: customer; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE fincore IN SCHEMA customer GRANT SELECT,INSERT,DELETE,UPDATE ON TABLES TO core_customer;

--
--
