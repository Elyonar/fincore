-- =============================================================================================
-- organization — the schema, in one migration.
--
-- Collapsed from 2 migrations (V1–V2) before the first release. Every one of them had
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
-- Name: organization; Type: SCHEMA; Schema: -; Owner: -
--

--
--

--
-- Name: organization; Type: SCHEMA; Schema: -; Owner: -
--

-- Flyway creates the schema from its own `schemas` setting; kept so the file also stands
-- alone if it is ever applied by hand.
CREATE SCHEMA IF NOT EXISTS organization;

--
-- Name: current_tenant(); Type: FUNCTION; Schema: organization; Owner: -
--

CREATE FUNCTION organization.current_tenant() RETURNS uuid
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
-- Name: organizational_units; Type: TABLE; Schema: organization; Owner: -
--

CREATE TABLE organization.organizational_units (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    parent_unit_id uuid,
    unit_type text NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    closed_at timestamp with time zone,
    CONSTRAINT closed_units_are_stamped CHECK ((((status = 'CLOSED'::text) AND (closed_at IS NOT NULL)) OR ((status <> 'CLOSED'::text) AND (closed_at IS NULL)))),
    CONSTRAINT organizational_units_code_check CHECK (((length(code) >= 1) AND (length(code) <= 100))),
    CONSTRAINT organizational_units_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'CLOSED'::text]))),
    CONSTRAINT organizational_units_unit_type_check CHECK ((unit_type = ANY (ARRAY['BRANCH'::text, 'REGION'::text, 'COUNTRY_OPERATION'::text, 'BUSINESS_LINE'::text, 'CENTRE'::text, 'AGENT_NETWORK'::text, 'DIGITAL_CHANNEL'::text, 'OPERATIONS_TEAM'::text])))
);

ALTER TABLE ONLY organization.organizational_units FORCE ROW LEVEL SECURITY;

--
-- Name: unit_assignments; Type: TABLE; Schema: organization; Owner: -
--

CREATE TABLE organization.unit_assignments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    unit_id uuid NOT NULL,
    principal text NOT NULL,
    assigned_by text NOT NULL,
    assigned_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_by text,
    revoked_at timestamp with time zone,
    CONSTRAINT revocations_are_attributed CHECK ((((revoked_at IS NULL) AND (revoked_by IS NULL)) OR ((revoked_at IS NOT NULL) AND (revoked_by IS NOT NULL))))
);

ALTER TABLE ONLY organization.unit_assignments FORCE ROW LEVEL SECURITY;

--
-- Name: organizational_units organizational_units_code_unique; Type: CONSTRAINT; Schema: organization; Owner: -
--

ALTER TABLE ONLY organization.organizational_units
    ADD CONSTRAINT organizational_units_code_unique UNIQUE (tenant_id, code);

--
-- Name: organizational_units organizational_units_pkey; Type: CONSTRAINT; Schema: organization; Owner: -
--

ALTER TABLE ONLY organization.organizational_units
    ADD CONSTRAINT organizational_units_pkey PRIMARY KEY (id);

--
-- Name: organizational_units organizational_units_tenant_id_id_key; Type: CONSTRAINT; Schema: organization; Owner: -
--

ALTER TABLE ONLY organization.organizational_units
    ADD CONSTRAINT organizational_units_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: unit_assignments unit_assignments_pkey; Type: CONSTRAINT; Schema: organization; Owner: -
--

ALTER TABLE ONLY organization.unit_assignments
    ADD CONSTRAINT unit_assignments_pkey PRIMARY KEY (id);

--
-- Name: unit_assignments unit_assignments_tenant_id_id_key; Type: CONSTRAINT; Schema: organization; Owner: -
--

ALTER TABLE ONLY organization.unit_assignments
    ADD CONSTRAINT unit_assignments_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: one_live_assignment_per_principal_per_unit; Type: INDEX; Schema: organization; Owner: -
--

CREATE UNIQUE INDEX one_live_assignment_per_principal_per_unit ON organization.unit_assignments USING btree (tenant_id, unit_id, principal) WHERE (revoked_at IS NULL);

--
-- Name: organizational_units_by_parent; Type: INDEX; Schema: organization; Owner: -
--

CREATE INDEX organizational_units_by_parent ON organization.organizational_units USING btree (tenant_id, parent_unit_id);

--
-- Name: unit_assignments_by_principal; Type: INDEX; Schema: organization; Owner: -
--

CREATE INDEX unit_assignments_by_principal ON organization.unit_assignments USING btree (tenant_id, principal) WHERE (revoked_at IS NULL);

--
-- Name: organizational_units organizational_units_tenant_id_parent_unit_id_fkey; Type: FK CONSTRAINT; Schema: organization; Owner: -
--

ALTER TABLE ONLY organization.organizational_units
    ADD CONSTRAINT organizational_units_tenant_id_parent_unit_id_fkey FOREIGN KEY (tenant_id, parent_unit_id) REFERENCES organization.organizational_units(tenant_id, id);

--
-- Name: unit_assignments unit_assignments_tenant_id_unit_id_fkey; Type: FK CONSTRAINT; Schema: organization; Owner: -
--

ALTER TABLE ONLY organization.unit_assignments
    ADD CONSTRAINT unit_assignments_tenant_id_unit_id_fkey FOREIGN KEY (tenant_id, unit_id) REFERENCES organization.organizational_units(tenant_id, id);

--
-- Name: organizational_units; Type: ROW SECURITY; Schema: organization; Owner: -
--

ALTER TABLE organization.organizational_units ENABLE ROW LEVEL SECURITY;

--
-- Name: organizational_units organizational_units_tenant_isolation; Type: POLICY; Schema: organization; Owner: -
--

CREATE POLICY organizational_units_tenant_isolation ON organization.organizational_units USING ((tenant_id = organization.current_tenant())) WITH CHECK ((tenant_id = organization.current_tenant()));

--
-- Name: unit_assignments; Type: ROW SECURITY; Schema: organization; Owner: -
--

ALTER TABLE organization.unit_assignments ENABLE ROW LEVEL SECURITY;

--
-- Name: unit_assignments unit_assignments_tenant_isolation; Type: POLICY; Schema: organization; Owner: -
--

CREATE POLICY unit_assignments_tenant_isolation ON organization.unit_assignments USING ((tenant_id = organization.current_tenant())) WITH CHECK ((tenant_id = organization.current_tenant()));

--
-- Name: SCHEMA organization; Type: ACL; Schema: -; Owner: -
--

GRANT USAGE ON SCHEMA organization TO core_organization;

--
-- Name: TABLE organizational_units; Type: ACL; Schema: organization; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE organization.organizational_units TO core_organization;

--
-- Name: TABLE unit_assignments; Type: ACL; Schema: organization; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE organization.unit_assignments TO core_organization;

--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: organization; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE fincore IN SCHEMA organization GRANT SELECT,USAGE ON SEQUENCES TO core_organization;

--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: organization; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE fincore IN SCHEMA organization GRANT SELECT,INSERT,DELETE,UPDATE ON TABLES TO core_organization;

--
--
