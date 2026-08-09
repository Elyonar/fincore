-- =============================================================================================
-- What people are called, and how they are numbered.
--
-- V4 gave the staff record a `job_title` and a `staff_number` and left both as free text set once,
-- at creation. That is enough to store a value and not enough to make it mean anything: three
-- administrators hiring three tellers produce "Teller", "teller" and "Cashier/Teller", and the
-- staff number — the identifier payroll and every internal reference use — is whatever somebody
-- typed after checking a spreadsheet, or nothing at all.
--
-- So both become institution-level decisions rather than per-hire typing.
--
-- A job title is deliberately NOT a role. A role is what somebody may do and is enforced on every
-- request; a title is what they are called and is enforced nowhere. Conflating them is how
-- institutions end up with `job:teller-lagos` — a permission set multiplied by a place, which
-- ADR 0012 exists to prevent. Keeping the vocabulary separate keeps roles about authority.
-- =============================================================================================

CREATE TABLE auth.job_titles (
    tenant_id   UUID        NOT NULL REFERENCES auth.tenants (id),
    -- Stored as authored, including case: "Head of Operations" is how it should appear on a
    -- profile. Uniqueness is case-insensitive below, so it cannot be authored twice.
    title       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT        NOT NULL,
    PRIMARY KEY (tenant_id, title)
);

-- "Teller" and "teller" are the same job. The primary key alone would allow both.
CREATE UNIQUE INDEX job_titles_one_spelling_per_tenant
    ON auth.job_titles (tenant_id, lower(title));

ALTER TABLE auth.job_titles ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.job_titles FORCE ROW LEVEL SECURITY;
CREATE POLICY job_titles_tenant ON auth.job_titles
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, DELETE ON auth.job_titles TO identity_app;

-- ---------------------------------------------------------------------------------------------
-- Staff numbering.
--
-- One row per tenant describing how the next staff number is formed. `next_value` is claimed with
-- an UPDATE ... RETURNING inside the creating transaction, so two administrators hiring at the
-- same moment take different numbers — the row lock is the arbiter, which is the same discipline
-- every other counter on this platform follows. A sequence would not do: sequences are not
-- tenant-scoped, and a gap-free-looking number that silently skips on rollback is worse than an
-- honest counter an institution can reset.
--
-- An institution that already numbers its staff can keep doing so: `staff_number` stays
-- overridable at creation, and this table simply supplies a default when nothing is given.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE auth.staff_numbering (
    tenant_id  UUID        NOT NULL PRIMARY KEY REFERENCES auth.tenants (id),
    -- Free text so an institution's existing convention fits: "STF-", "ACME/", "".
    prefix     TEXT        NOT NULL DEFAULT 'STF-',
    -- Zero-padded width of the numeric part. 4 gives STF-0001 through STF-9999 and does not stop
    -- there — a wider number simply stops being padded rather than failing.
    width      INT         NOT NULL DEFAULT 4 CHECK (width BETWEEN 1 AND 12),
    next_value BIGINT      NOT NULL DEFAULT 1 CHECK (next_value >= 1),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT        NOT NULL
);

ALTER TABLE auth.staff_numbering ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.staff_numbering FORCE ROW LEVEL SECURITY;
CREATE POLICY staff_numbering_tenant ON auth.staff_numbering
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, UPDATE ON auth.staff_numbering TO identity_app;

-- No seeding here, for the reason V3 and V4 give: migrations run as the owner with no tenant
-- context against FORCE ROW LEVEL SECURITY tables, so an INSERT ... SELECT over tenants would
-- appear to succeed and write nothing. ManifestSeeder creates the numbering row per tenant.
