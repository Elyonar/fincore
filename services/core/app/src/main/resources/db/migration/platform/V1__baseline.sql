-- =============================================================================================
-- platform — the schema, in one migration.
--
-- Collapsed from V1–V2 before the first release, for the reasons written at length in the other
-- modules' baselines: nothing yet depends on the history, and after the first release nothing may
-- collapse again.
-- =============================================================================================

--
--

--
-- Name: platform; Type: SCHEMA; Schema: -; Owner: -
--

--
--

--
-- Name: platform; Type: SCHEMA; Schema: -; Owner: -
--

-- Flyway creates the schema from its own `schemas` setting; kept so the file also stands
-- alone if it is ever applied by hand.
CREATE SCHEMA IF NOT EXISTS platform;

--
-- Name: tenants; Type: TABLE; Schema: platform; Owner: -
--

CREATE TABLE platform.tenants (
    id uuid NOT NULL,
    name text NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by text NOT NULL,
    business_timezone text DEFAULT 'Africa/Lagos'::text NOT NULL,
    CONSTRAINT tenants_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'SUSPENDED'::text])))
);

--
-- Name: TABLE tenants; Type: COMMENT; Schema: platform; Owner: -
--

COMMENT ON TABLE platform.tenants IS 'The registry a tenant must appear in before Core will serve it. Provisioned deliberately, never implied by a token. Deliberately NOT row-level secured: a request must be able to ask "is this tenant real?" before it has a tenant context to be scoped by. Holds a name and a status — no money, no PII.';

--
-- Name: tenants tenants_pkey; Type: CONSTRAINT; Schema: platform; Owner: -
--

ALTER TABLE ONLY platform.tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);

--
-- Name: SCHEMA platform; Type: ACL; Schema: -; Owner: -
--

GRANT USAGE ON SCHEMA platform TO core_customer;
GRANT USAGE ON SCHEMA platform TO core_product;
GRANT USAGE ON SCHEMA platform TO core_orchestration;
GRANT USAGE ON SCHEMA platform TO core_worker;

--
-- Name: TABLE tenants; Type: ACL; Schema: platform; Owner: -
--

GRANT SELECT ON TABLE platform.tenants TO core_customer;
GRANT SELECT ON TABLE platform.tenants TO core_product;
GRANT SELECT ON TABLE platform.tenants TO core_orchestration;
GRANT SELECT ON TABLE platform.tenants TO core_worker;

--
--
