-- The six tables, and the rules that live in the database rather than in code.
--
-- Design: services/notification/docs/design.md (AGREED v1.1). Anything here that contradicts that
-- document is a bug, not a variation.

-- ---------------------------------------------------------------------------------------------
-- The channel registry (D-13)
-- ---------------------------------------------------------------------------------------------
--
-- A channel is a row, deliberately, and not a CHECK-constrained enumeration. A CHECK means a
-- migration for every new channel, and that single choice is the difference between "add a
-- channel" and "change the schema". Push is already in the PRD; WhatsApp is a question somebody
-- will ask.
--
-- Not tenant-scoped: which channels the platform can speak is a deployment fact, not a tenant's.
-- What a *tenant* does with them is channel_policy, which is tenant-scoped.
CREATE TABLE notification.channels (
    id             TEXT        PRIMARY KEY,
    -- What kind of address this channel needs. Several channels share one: SMS and WhatsApp are
    -- both PHONE, which is exactly why Customer returns addresses keyed by kind and a new channel
    -- on an existing kind costs nothing outside this service.
    address_kind   TEXT        NOT NULL,
    -- The template parts a message on this channel must have. SMS is {body}; email is
    -- {subject,body}; push would be {title,body}. Validated at publish, so a template that cannot
    -- render is refused before it is ever selected for a customer.
    required_parts TEXT[]      NOT NULL CHECK (cardinality(required_parts) > 0),
    -- SEGMENTED counts GSM-7/UCS-2 segments; PLAIN counts one unit per message. A new length model
    -- is the one genuinely new concept a future channel could bring, and this is where it belongs.
    content_model  TEXT        NOT NULL CHECK (content_model IN ('SEGMENTED', 'PLAIN')),
    max_units      INT         NOT NULL CHECK (max_units > 0),
    enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The two v1 channels. Adding a third is an INSERT and a sender class.
INSERT INTO notification.channels (id, address_kind, required_parts, content_model, max_units) VALUES
    ('SMS',   'PHONE', ARRAY['body'],            'SEGMENTED', 3),
    ('EMAIL', 'EMAIL', ARRAY['subject', 'body'], 'PLAIN',     1);

-- ---------------------------------------------------------------------------------------------
-- Templates (D-11, D-12)
-- ---------------------------------------------------------------------------------------------
--
-- Versions are append-only and a published one is never edited: a sent message records the version
-- that produced it, and "what did we actually send that customer in March" has to stay answerable.
-- The same shape product_versions uses — version orders history, effective_from decides which is
-- live — because one rule learned once beats two rules that are nearly the same.
CREATE TABLE notification.templates (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL,
    template_key   TEXT        NOT NULL,
    channel        TEXT        NOT NULL REFERENCES notification.channels (id),
    locale         TEXT        NOT NULL,
    version        INT         NOT NULL CHECK (version > 0),
    status         TEXT        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED')),
    -- Named parts rather than fixed columns, because which parts exist is the channel's business
    -- (D-13). The registry says which are required; the trigger below enforces it.
    parts          JSONB       NOT NULL,
    units          INT,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by   TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    CONSTRAINT templates_version_unique UNIQUE (tenant_id, template_key, channel, locale, version),
    CONSTRAINT published_is_attributed CHECK (status <> 'PUBLISHED' OR published_by IS NOT NULL),
    CONSTRAINT published_is_measured CHECK (status <> 'PUBLISHED' OR units IS NOT NULL)
);

-- A published template must carry every part its channel requires.
--
-- This is the guarantee that moved when parts became JSONB (CHANGELOG v1.1): it used to be a
-- NOT NULL on a subject column. It must not weaken in the move, so it is a trigger rather than an
-- application check — an email template with no subject renders a message with no subject, and the
-- first time anyone notices is in a customer's inbox.
CREATE OR REPLACE FUNCTION notification.template_has_required_parts() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    missing TEXT;
BEGIN
    IF NEW.status <> 'PUBLISHED' THEN
        RETURN NEW;
    END IF;

    SELECT part INTO missing
      FROM notification.channels c, unnest(c.required_parts) AS part
     WHERE c.id = NEW.channel
       AND NOT (NEW.parts ? part)
     LIMIT 1;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'template is missing the part "%" that channel % requires', missing, NEW.channel
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER templates_require_channel_parts
    BEFORE INSERT OR UPDATE ON notification.templates
    FOR EACH ROW EXECUTE FUNCTION notification.template_has_required_parts();

-- A published version is immutable. Editing one silently changes what a past message said it said.
CREATE OR REPLACE FUNCTION notification.published_templates_are_immutable() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'a published template version is immutable; publish a new version'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER templates_published_immutable
    BEFORE UPDATE OR DELETE ON notification.templates
    FOR EACH ROW EXECUTE FUNCTION notification.published_templates_are_immutable();

-- ---------------------------------------------------------------------------------------------
-- Delivery policy per tenant (D-9, D-10, D-13)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE notification.channel_policy (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL,
    category      TEXT        NOT NULL CHECK (category IN ('TRANSACTIONAL', 'SERVICE', 'MARKETING')),
    -- Ordered fallback. A customer with no address for the first channel falls through to the
    -- next; one with no address for any of them is a suppression, never a silent drop.
    channels      TEXT[]      NOT NULL CHECK (cardinality(channels) > 0),
    -- Tenant-local, like the ledger's business date, so two services never disagree about what
    -- "today" means.
    timezone      TEXT        NOT NULL DEFAULT 'Africa/Lagos',
    quiet_from    TIME,
    quiet_to      TIME,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    CONSTRAINT one_policy_per_category UNIQUE (tenant_id, category),
    -- Both or neither. A window with one end is a window nobody can evaluate.
    CONSTRAINT quiet_hours_are_complete CHECK ((quiet_from IS NULL) = (quiet_to IS NULL))
);

-- ---------------------------------------------------------------------------------------------
-- Consumed events — the deduplication ledger (D-4)
-- ---------------------------------------------------------------------------------------------
--
-- At-least-once is the contract (ADR 0008), so this table is its price. The unique key is
-- (publisher, event_id) exactly as the ADR specifies, and `disposition` is what makes invariant 1
-- checkable: every consumed event reaches a terminal answer, and none is silently dropped.
CREATE TABLE notification.consumed_events (
    id          BIGSERIAL   PRIMARY KEY,
    publisher   TEXT        NOT NULL,
    event_id    BIGINT      NOT NULL,
    tenant_id   UUID        NOT NULL,
    event_type  TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    epoch       BIGINT      NOT NULL,
    disposition TEXT        NOT NULL CHECK (disposition IN ('NOTIFIED', 'SUPPRESSED', 'IGNORED')),
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT one_disposition_per_event UNIQUE (publisher, event_id)
);

-- ---------------------------------------------------------------------------------------------
-- Notifications — one row per message owed (D-4)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE notification.notifications (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    -- The business moment, not the event id. Two different events describing one transfer must not
    -- produce two messages, and (publisher, event_id) cannot see that they are the same moment —
    -- it stops redelivery of one event, not two distinct events (D-3, D-4).
    business_moment_key TEXT        NOT NULL,
    category            TEXT        NOT NULL CHECK (category IN ('TRANSACTIONAL', 'SERVICE', 'MARKETING')),
    channel             TEXT        NOT NULL REFERENCES notification.channels (id),
    template_key        TEXT        NOT NULL,
    template_version    INT         NOT NULL,
    locale              TEXT        NOT NULL,
    recipient_ref       UUID        NOT NULL,
    -- PII at rest (D-8). Stored because a delivery record must say where a message went; encrypted
    -- at the application boundary, never logged, and purged on a retention schedule.
    recipient_address   TEXT        NOT NULL,
    rendered            JSONB       NOT NULL,
    units               INT         NOT NULL CHECK (units > 0),
    state               TEXT        NOT NULL DEFAULT 'PENDING'
                                    CHECK (state IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    attempts            INT         NOT NULL DEFAULT 0,
    claimed_by          TEXT,
    claim_expires_at    TIMESTAMPTZ,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    -- The backstop of D-4's second level. It survives a mistake in "one category, one publisher",
    -- a redriven topic, and a future second publisher — none of which the event-id key can see.
    CONSTRAINT one_message_per_moment UNIQUE (tenant_id, business_moment_key, category, channel, recipient_ref)
);

CREATE INDEX notifications_due ON notification.notifications (next_attempt_at)
    WHERE state IN ('PENDING', 'SENDING');

-- Terminal states are terminal, enforced rather than asserted in code — the discipline Core
-- already applies to sagas. A message that reports SENT and then moves again is a message nobody
-- can account for.
CREATE OR REPLACE FUNCTION notification.terminal_states_are_terminal() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('SENT', 'FAILED') AND NEW.state IS DISTINCT FROM OLD.state THEN
        RAISE EXCEPTION 'notification % is already %, and terminal', OLD.id, OLD.state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER notifications_terminal
    BEFORE UPDATE ON notification.notifications
    FOR EACH ROW EXECUTE FUNCTION notification.terminal_states_are_terminal();

-- ---------------------------------------------------------------------------------------------
-- Delivery attempts — append-only (D-18, D-19)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE notification.delivery_attempts (
    id               BIGSERIAL   PRIMARY KEY,
    tenant_id        UUID        NOT NULL,
    notification_id  UUID        NOT NULL,
    attempt_no       INT         NOT NULL CHECK (attempt_no > 0),
    -- The three-valued shape the platform already uses on the money path, for the same reason: a
    -- timeout is not a failure, and treating it as one is how a duplicate or a silence happens.
    outcome          TEXT        NOT NULL CHECK (outcome IN ('SENT', 'DEFINITE_FAILURE', 'UNKNOWN')),
    -- Passed to the gateway so one that supports deduplication can apply it. Derived, never
    -- random: a retry of the same attempt must present the same reference or it is not a retry.
    client_reference TEXT        NOT NULL,
    gateway_ref      TEXT,
    error_code       TEXT,
    attempted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, notification_id) REFERENCES notification.notifications (tenant_id, id),
    CONSTRAINT one_row_per_attempt UNIQUE (tenant_id, notification_id, attempt_no)
);

CREATE OR REPLACE FUNCTION notification.attempts_are_append_only() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'notification.delivery_attempts is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER delivery_attempts_no_update
    BEFORE UPDATE OR DELETE ON notification.delivery_attempts
    FOR EACH ROW EXECUTE FUNCTION notification.attempts_are_append_only();

-- ---------------------------------------------------------------------------------------------
-- Suppressions — why a message was not sent (D-17)
-- ---------------------------------------------------------------------------------------------
--
-- The service's defining invariant lives here: every consumed event ends as a message or as a row
-- in this table. "Why did my customer not get an SMS?" is answered by a query, not by reading logs
-- and guessing. Reason codes are a closed set, documented and catalog-tested — an open text field
-- would become English prose a caller has to parse, which hard rule 9 forbids for exactly this
-- reason.
CREATE TABLE notification.suppressions (
    id            BIGSERIAL   PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    publisher     TEXT,
    event_id      BIGINT,
    business_moment_key TEXT,
    category      TEXT,
    channel       TEXT,
    recipient_ref UUID,
    reason_code   TEXT        NOT NULL,
    detail        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX suppressions_by_moment ON notification.suppressions (tenant_id, business_moment_key);

-- ---------------------------------------------------------------------------------------------
-- Row-level security (ADR 0007) and grants
-- ---------------------------------------------------------------------------------------------
--
-- consumed_events is deliberately tenant-scoped too, even though the dedupe key is global: a
-- consumer that could read another tenant's event history would leak which tenants are busy, and
-- there is no reason it needs to.
ALTER TABLE notification.templates          ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.templates          FORCE  ROW LEVEL SECURITY;
ALTER TABLE notification.channel_policy     ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.channel_policy     FORCE  ROW LEVEL SECURITY;
ALTER TABLE notification.consumed_events    ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.consumed_events    FORCE  ROW LEVEL SECURITY;
ALTER TABLE notification.notifications      ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.notifications      FORCE  ROW LEVEL SECURITY;
ALTER TABLE notification.delivery_attempts  ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.delivery_attempts  FORCE  ROW LEVEL SECURITY;
ALTER TABLE notification.suppressions       ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.suppressions       FORCE  ROW LEVEL SECURITY;

CREATE POLICY templates_tenant_isolation ON notification.templates
    USING (tenant_id = notification.current_tenant())
    WITH CHECK (tenant_id = notification.current_tenant());

CREATE POLICY channel_policy_tenant_isolation ON notification.channel_policy
    USING (tenant_id = notification.current_tenant())
    WITH CHECK (tenant_id = notification.current_tenant());

CREATE POLICY consumed_events_tenant_isolation ON notification.consumed_events
    USING (tenant_id = notification.current_tenant())
    WITH CHECK (tenant_id = notification.current_tenant());

CREATE POLICY notifications_tenant_isolation ON notification.notifications
    USING (tenant_id = notification.current_tenant())
    WITH CHECK (tenant_id = notification.current_tenant());

CREATE POLICY delivery_attempts_tenant_isolation ON notification.delivery_attempts
    USING (tenant_id = notification.current_tenant())
    WITH CHECK (tenant_id = notification.current_tenant());

CREATE POLICY suppressions_tenant_isolation ON notification.suppressions
    USING (tenant_id = notification.current_tenant())
    WITH CHECK (tenant_id = notification.current_tenant());

-- The worker's cross-tenant reach, granted table by table and no wider. It reads and updates the
-- queue and appends attempts; it can neither author a notification nor read a template, because
-- neither is its job.
CREATE POLICY notifications_worker_access ON notification.notifications
    USING (notification.is_worker())
    WITH CHECK (notification.is_worker());

CREATE POLICY delivery_attempts_worker_access ON notification.delivery_attempts
    USING (notification.is_worker())
    WITH CHECK (notification.is_worker());

CREATE POLICY suppressions_worker_access ON notification.suppressions
    USING (notification.is_worker())
    WITH CHECK (notification.is_worker());

GRANT SELECT ON notification.channels TO notification_app, notification_worker;
GRANT SELECT, INSERT, UPDATE ON notification.templates TO notification_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON notification.channel_policy TO notification_app;
GRANT SELECT, INSERT ON notification.consumed_events TO notification_app;
GRANT SELECT, INSERT, UPDATE ON notification.notifications TO notification_app;
GRANT SELECT, INSERT ON notification.delivery_attempts TO notification_app;
GRANT SELECT, INSERT ON notification.suppressions TO notification_app;

GRANT SELECT, UPDATE ON notification.notifications TO notification_worker;
GRANT SELECT, INSERT ON notification.delivery_attempts TO notification_worker;
GRANT SELECT, INSERT ON notification.suppressions TO notification_worker;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA notification TO notification_app, notification_worker;
