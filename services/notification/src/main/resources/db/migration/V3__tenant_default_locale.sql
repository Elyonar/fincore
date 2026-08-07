-- The language a tenant writes in when the customer never said.
--
-- Locale selection needs two facts and had neither: what this customer speaks, and what to do when
-- nobody asked. Customer now carries the first (customer V6). This is the second.
--
-- It lives beside `timezone` because they are the same kind of setting — how to address this
-- tenant's customers — and a tenant that writes in Hausa and runs on Africa/Lagos configures both
-- in one place. NOT NULL with a default, unlike the customer's own locale: a tenant must always
-- have a language to fall back to, or the fallback is not one.
ALTER TABLE notification.channel_policy
    ADD COLUMN default_locale TEXT NOT NULL DEFAULT 'en';

COMMENT ON COLUMN notification.channel_policy.default_locale IS
    'Used when the customer has no locale of their own, and as the second attempt when they do but '
    'the tenant has published no template in it. A tenant with only English templates still reaches '
    'a customer who speaks Yoruba.';
