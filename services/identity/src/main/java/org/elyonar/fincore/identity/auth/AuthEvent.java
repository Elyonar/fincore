package org.elyonar.fincore.identity.auth;

/**
 * The vocabulary of {@code auth.auth_events} — every value this service writes to the audit trail.
 *
 * <p>Constants rather than literals at each call site (hard rule 10). These strings are read by
 * something other than this codebase: an examiner filtering a trail, an alert on a spike of
 * {@code LOGIN_FAILED}, a report grouping by cause. A typo in one of five spellings of
 * {@code LOGIN_FAILED} does not fail a build or a test — it silently splits one series into two
 * and quietly under-reports the thing being watched for.
 *
 * <p>Kept as constants and not an enum on purpose: the column is TEXT, an older row may carry a
 * value this version no longer writes, and reading history must never depend on the current
 * release's enum being exhaustive.
 */
public final class AuthEvent {

    private AuthEvent() {}

    // --- events -----------------------------------------------------------------------------

    /** A credential was presented and refused. The cause says which refusal; the wire never does. */
    public static final String LOGIN_FAILED = "LOGIN_FAILED";

    /** Tokens were granted. */
    public static final String LOGIN_OK = "LOGIN_OK";

    /** The credential was right and something is owed first — a password change, a second factor. */
    public static final String ACTION_REQUIRED = "ACTION_REQUIRED";

    /** A second factor was presented and refused. */
    public static final String MFA_FAILED = "MFA_FAILED";

    /** A refresh token was exchanged for its successor. */
    public static final String ROTATED = "ROTATED";

    /** A spent refresh token was presented again: the theft signal, and the family it killed. */
    public static final String REUSE_REVOKED = "REUSE_REVOKED";

    /** The holder ended their own session. */
    public static final String LOGOUT = "LOGOUT";

    /** Every session for a user was revoked at once. */
    public static final String SESSIONS_REVOKED = "SESSIONS_REVOKED";

    /** A password was changed — by its owner, or forced on first use. */
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    /** A member of staff was created — by the manifest at bootstrap, or by an administrator. */
    public static final String USER_CREATED = "USER_CREATED";

    /** A tenant-authored role was created. */
    public static final String ROLE_CREATED = "ROLE_CREATED";

    /** What a role grants was replaced. */
    public static final String ROLE_CHANGED = "ROLE_CHANGED";

    /** A custom role was removed. */
    public static final String ROLE_DELETED = "ROLE_DELETED";

    /** A user's role grants were replaced. */
    public static final String ROLES_CHANGED = "ROLES_CHANGED";

    /** A member of staff completed their own record and opened the onboarding gate. */
    public static final String PROFILE_COMPLETED = "PROFILE_COMPLETED";

    /** A member of staff changed their own details. */
    public static final String PROFILE_UPDATED = "PROFILE_UPDATED";

    /** A user's organizational unit assignments were replaced (ADR 0012, ADR 0017). */
    public static final String UNITS_CHANGED = "UNITS_CHANGED";

    /** An administrator issued a fresh temporary credential and revoked the user's sessions. */
    public static final String PASSWORD_RESET = "PASSWORD_RESET";

    /** An administrator cleared a lockout before it expired. */
    public static final String UNLOCKED = "UNLOCKED";

    // --- causes, the detail on a refusal ------------------------------------------------------

    /** The key every cause is recorded under. */
    public static final String CAUSE = "cause";

    /** No such username in this tenant. Recorded; never disclosed. */
    public static final String CAUSE_UNKNOWN_USER = "UNKNOWN_USER";

    /** The username exists and the secret was wrong. */
    public static final String CAUSE_BAD_CREDENTIAL = "BAD_CREDENTIAL";

    /** The secret was right and the account was locked, which answers identically on the wire. */
    public static final String CAUSE_LOCKED = "LOCKED";

    /** The secret was right and the account is not ACTIVE. */
    public static final String CAUSE_DISABLED = "DISABLED";

    // --- user status --------------------------------------------------------------------------

    /** The only status that may hold tokens. Anything else refuses in the one voice. */
    public static final String STATUS_ACTIVE = "ACTIVE";
}
