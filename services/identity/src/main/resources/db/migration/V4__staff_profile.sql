-- =============================================================================================
-- The staff record, kept deliberately small.
--
-- V1 held what authentication needs: a username, a name, an email. A bank needs a little more to
-- employ somebody who moves money — which staff number payroll knows them by, what they do, and a
-- number to reach them on. It does not need their life history in a login service: identifying a
-- person to a regulator's standard is the KYC process's job, for staff as much as for customers,
-- and that process will own those fields when it exists. Putting them here first would mean
-- migrating them out later, from the one service whose blast radius should stay smallest.
--
-- The split below is the design: the administered half is set by whoever administers staff, the
-- self-declared half only by the person themselves. A person editing their own job title is not a
-- control; a person supplying their own phone number is the only reliable way to get it.
--
-- `profile_completed_at` is the onboarding gate. A timestamp rather than a boolean, so "when did
-- this person complete their record" has an answer, and so a future re-attestation is a nullable
-- set rather than a migration.
-- =============================================================================================

ALTER TABLE auth.users
    -- Administered: set at creation by an administrator, never by the holder.
    ADD COLUMN staff_number TEXT,
    ADD COLUMN job_title    TEXT,
    ADD COLUMN started_on   DATE,

    -- Self-declared: the person's own contact number, and the channel a one-time code will use.
    ADD COLUMN phone        TEXT,

    -- The gate. NULL means the portal holds them at onboarding.
    ADD COLUMN profile_completed_at TIMESTAMPTZ;

-- Payroll and every internal reference use the staff number, so two people cannot share one.
-- Partial, because it is optional until an institution decides to use it, and NULLs are not
-- duplicates of each other.
CREATE UNIQUE INDEX users_staff_number_per_tenant
    ON auth.users (tenant_id, staff_number)
    WHERE staff_number IS NOT NULL;

-- The seeded super-administrator's exemption from the gate is applied by ManifestSeeder, not
-- here: migrations run as the owner with no tenant context and these tables are FORCE ROW LEVEL
-- SECURITY, so an UPDATE at this point matches nothing and would look like it had worked.
