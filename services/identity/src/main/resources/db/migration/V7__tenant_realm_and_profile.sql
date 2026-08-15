-- =============================================================================================
-- The realm stops being discarded, and the tenant gains the profile it was already describing.
--
-- ADR 0023. Two defects, one table.
--
-- FIRST: login could not name its institution. `LoginRequest` carries {username, password,
-- clientId} and nothing else, so the tenant had to be inferred from the instance — which works
-- for exactly one active tenant and refuses to guess beyond that. The manifest has validated a
-- `realm` per tenant since ADR 0016, checked it for duplicates, and then thrown it away because
-- there was no column to put it in. This is that column. Login keys on (realm, username), which
-- is what `auth.users` was already keyed for.
--
-- SECOND: `displayName`, `countryCode`, `segment` and `webOrigin` are validated by ManifestSeeder
-- and likewise discarded — only `legalName` survives, as `name`. Nothing could read back what an
-- institution is called on a screen, what country it operates in, or which origin its web client
-- is served from. All four are the manifest's own vocabulary; none is new information.
--
-- NULLABLE ON PURPOSE. An instance already running has tenants with no realm, and there is no
-- source to backfill one from — the manifest is a deployment's file, not this migration's. The
-- seeder converges them on the next boot, additively, the way it converges everything else. The
-- unique index therefore ignores nulls rather than blocking the migration on rows it cannot fix.
-- =============================================================================================

ALTER TABLE auth.tenants ADD COLUMN realm        TEXT;
ALTER TABLE auth.tenants ADD COLUMN display_name TEXT;
ALTER TABLE auth.tenants ADD COLUMN country_code TEXT;
ALTER TABLE auth.tenants ADD COLUMN segment      TEXT;
ALTER TABLE auth.tenants ADD COLUMN web_origin   TEXT;

-- A realm is a handle in a URL and a login form, not a display string: lower-case, no spaces, and
-- stable forever, because changing it locks every user of that institution out at once.
ALTER TABLE auth.tenants ADD CONSTRAINT tenants_realm_is_a_handle
    CHECK (realm IS NULL OR realm ~ '^[a-z0-9][a-z0-9-]{1,62}$');

-- Case-insensitively unique, because a login that resolved 'acme' and 'ACME' to two institutions
-- would be the wrong-bank bug this column exists to prevent. WHERE NOT NULL so tenants awaiting
-- their first convergent boot do not collide with each other on null.
CREATE UNIQUE INDEX tenants_realm_is_unique ON auth.tenants (lower(realm)) WHERE realm IS NOT NULL;

COMMENT ON COLUMN auth.tenants.realm IS
    'The institution''s login handle. Supplied at login to name which institution is being '
    'authenticated (ADR 0023); optional on a single-tenant instance, where the one active tenant '
    'is unambiguous.';
COMMENT ON COLUMN auth.tenants.country_code IS
    'ISO 3166-1 alpha-2. The platform is Nigeria-first and not Nigeria-only, and a cross-country '
    'deployment groups institutions by this.';

-- The registry is read by the application role to resolve a realm at login, which is a
-- pre-identity request: there is no tenant context to scope by yet, and this table has no RLS
-- policy for exactly that reason. SELECT was already granted at V1; the new columns ride it.
