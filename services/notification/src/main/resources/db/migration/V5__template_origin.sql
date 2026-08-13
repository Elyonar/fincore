-- Where a template's wording came from: this platform, or the institution.
--
-- An empty template table is why a new deployment tells nobody anything. The first transaction an
-- institution ever takes is suppressed NO_TEMPLATE, and the only trace is a reason code on a screen
-- nobody has opened yet. Seeding a starter set fixes that — but only if "seeded" and "written here"
-- stay tellable apart, for two reasons that both bite later:
--
--   * a starter can be improved in a later release, and improving one must never silently rewrite
--     wording an institution deliberately changed;
--   * an administrator looking at a template needs to know whether they are reading their own words
--     or the platform's, before they decide whether to edit them.
--
-- The mechanism is deliberately the one already there: a tenant "edits" by publishing their own
-- version of the same (key, channel, locale), and `Templates.live` already prefers the highest
-- published version. So a fork supersedes a starter without this column doing any work at send
-- time — it exists to answer the question, not to decide the outcome.
--
-- ADR 0017 made this split for permissions and roles: the platform owns the vocabulary, the tenant
-- owns the sentence. This is the same split applied to wording.

ALTER TABLE notification.templates
    ADD COLUMN origin TEXT NOT NULL DEFAULT 'TENANT'
        CHECK (origin IN ('PLATFORM', 'TENANT'));

COMMENT ON COLUMN notification.templates.origin IS
    'PLATFORM for a seeded starter, TENANT for wording this institution wrote. Never decides which '
    'version sends — highest published version does that — only who to attribute it to.';
