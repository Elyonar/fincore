-- The platform envelope (ADR 0008) becomes real on the wire.
--
-- The restore generation used to be written into the event payload as `ledgerEpoch`, and the
-- tenant and aggregate were duplicated in there too, because the relay published the payload
-- column verbatim and had nowhere else to put them. That made the ledger's messages a different
-- shape from Core's, gave one concept two names across two publishers, and — worst of the three —
-- left the outbox row id, the deduplication key ADR 0008 mandates, off the wire entirely.
--
-- Moving the epoch onto the row lets the shared renderer in libs/events emit all seven envelope
-- fields for every publisher from one place. `occurredAt` needs no column: created_at already
-- records when the writing transaction committed, which is exactly what the ADR asks for.
--
-- Default 1 is correct rather than convenient: the epoch starts at 1 and is only advanced by a
-- restore, so every row written before this migration was written under epoch 1.

ALTER TABLE outbox_events
    ADD COLUMN epoch BIGINT NOT NULL DEFAULT 1 CHECK (epoch > 0);

COMMENT ON COLUMN outbox_events.epoch IS
    'Restore generation at write time. Published as the envelope''s `epoch` so a consumer can '
    'discard events from a generation it has been told to distrust. Was `ledgerEpoch` inside the '
    'payload until V7.';
