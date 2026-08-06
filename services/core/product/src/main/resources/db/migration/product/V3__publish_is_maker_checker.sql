-- Maker-checker on publishing a product version.
--
-- `api.md` has always described publish as "attributed; maker-checker". V2 delivered half of it:
-- `published_is_attributed` proves *someone* published, but nothing stopped that someone from
-- being the person who wrote the version. Configuration is where limits and fees live, so one
-- person drafting a fee change and making it live is precisely the control this is meant to break.
--
-- Note where this is enforced. Orchestration has an `approvals` table, and Product cannot use it:
-- Product may not depend on Orchestration (ModuleBoundaryTest), and that approval is bound to a
-- saga id and an amount, neither of which a product version has. The property "the checker is not
-- the maker" does not need a shared table — it needs two names on one row, which is what this is.

-- Added with a default rather than backfilled with an UPDATE, and not for elegance: V2's
-- `product_versions_are_immutable_once_published` trigger refuses any UPDATE to a published row,
-- including this migration's own. It was right to — a migration is exactly the kind of privileged
-- path that quietly edits signed-off configuration, and the first draft of this file was caught by
-- it. ADD COLUMN ... DEFAULT fills existing rows as a metadata change, firing no row triggers.
--
-- The sentinel is a statement that the maker is unknown, not a guess. Attributing these rows to
-- whoever published them would manufacture an audit trail, and would also make the constraint
-- below pass for precisely the wrong reason.
ALTER TABLE product.product_versions
    ADD COLUMN created_by TEXT NOT NULL DEFAULT 'migrated:before-v3';

-- Dropped immediately: the default exists to serve rows that already existed. Every version
-- created from here on names its author, and a row that reaches this table without one is a bug
-- that should fail rather than silently inherit a sentinel.
ALTER TABLE product.product_versions ALTER COLUMN created_by DROP DEFAULT;

-- The control itself. Mirrors `checker_differs_from_maker` on orchestration.approvals, because it
-- is the same rule about a different subject, and a reader who has met one should recognise the
-- other on sight.
ALTER TABLE product.product_versions
    ADD CONSTRAINT publisher_differs_from_author
    CHECK (status <> 'PUBLISHED' OR published_by <> created_by);
