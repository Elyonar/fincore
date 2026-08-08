-- The tenant's business timezone becomes tenant configuration.
--
-- It was a hardcoded constant in two controllers ("moves to tenant configuration once that
-- exists" — this is that), and it stopped being cosmetic the moment DAILY limits became
-- enforced: the timezone decides when a customer's daily window rolls, so every tenant sharing
-- Lagos midnight was every non-Nigerian tenant getting the wrong regulatory day.
--
-- IANA zone id, defaulted rather than nullable: a tenant without a stated zone is a Nigerian
-- MFB until provisioning says otherwise (constitution 11 — build for Nigeria first), and a NULL
-- would push the default into application code where each reader picks its own.
ALTER TABLE platform.tenants
    ADD COLUMN business_timezone TEXT NOT NULL DEFAULT 'Africa/Lagos';
