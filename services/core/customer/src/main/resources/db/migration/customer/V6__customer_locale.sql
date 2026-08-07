-- What language to talk to this customer in.
--
-- PRD §4.9 asks for Hausa, Yoruba and Igbo templates as tenant content, and the notification
-- service keys every template by locale — so the schema was ready and the *choice* was not. With
-- nowhere to read a customer's language from, the sending service hardcoded `en` at the point where
-- the selection belongs, which is a working system that can only ever speak one language.
--
-- Nullable, deliberately. A customer whose language nobody asked for is the normal case, not an
-- error, and the sender falls back to the tenant's default rather than refusing to write. Storing
-- 'en' as a default here would be a guess wearing the shape of an answer — the same reason consent
-- stores only explicit answers.
ALTER TABLE customer.customers ADD COLUMN locale TEXT;

COMMENT ON COLUMN customer.customers.locale IS
    'BCP 47 language tag, e.g. en, ha, yo, ig. NULL means never asked: the sending service falls '
    'back to the tenant default rather than assuming English.';
