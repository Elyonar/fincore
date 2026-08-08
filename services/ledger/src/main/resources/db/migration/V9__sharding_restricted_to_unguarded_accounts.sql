-- Fan-in sharding is restricted to allow_negative accounts — now enforced, not just documented.
--
-- design.md and posting-algorithm.md state the restriction that makes sharding invariant-neutral:
-- a group of shards is summed for reporting, and only accounts that may go negative can be
-- sharded, because the negative-balance guard is per-account — a guarded account split across
-- shards could go negative in aggregate while every shard stays clean. The docs called the
-- restriction load-bearing; nothing enforced it, so a guarded account could be sharded today.
--
-- A trigger rather than a CHECK, matching the schema's own convention for cross-column rules the
-- application must not be able to forget (entries_are_append_only and friends). BEFORE INSERT OR
-- UPDATE, so neither creation nor a later edit can produce the forbidden shape.
CREATE OR REPLACE FUNCTION reject_guarded_sharding() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.group_ref IS NOT NULL AND NOT NEW.allow_negative THEN
        RAISE EXCEPTION
            'account % is guarded (allow_negative = false) and must not be sharded (group_ref %)',
            NEW.id, NEW.group_ref
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_sharding_requires_allow_negative
    BEFORE INSERT OR UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION reject_guarded_sharding();
