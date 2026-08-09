package org.elyonar.fincore.identity.directory;

/**
 * The directory's refusals (AGENTS.md hard rule 9), named where they are thrown.
 *
 * <p>Unlike the authentication surface — one uniform {@code AUTH_FAILED}, deliberately
 * uninformative because every distinguishing reason there is an oracle an attacker farms — the
 * directory answers an already-authenticated administrator about records they are entitled to
 * see. Vagueness here is not a control; it is a support ticket.
 *
 * <p>Core translates where its own catalog spells a code differently (admin-surface §5:
 * {@code ROLE_NOT_FOUND} for this service's {@code ROLE_UNKNOWN}).
 */
public final class DirectoryErrors {

    private DirectoryErrors() {}

    /** No such user in this tenant — indistinguishable from another tenant's user. */
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    /** The username is taken. The unique index arbitrates, never a pre-check. */
    public static final String USER_EXISTS = "USER_EXISTS";

    /** A role named in the request does not exist for this tenant. */
    public static final String ROLE_UNKNOWN = "ROLE_UNKNOWN";

    /** A permission named in the request is not in the platform catalog (ADR 0017). */
    public static final String PERMISSION_UNKNOWN = "PERMISSION_UNKNOWN";

    /** ADR 0017 guardrail 1: nobody grants what they do not hold. {@code details.permissions}. */
    public static final String PERMISSION_NOT_HELD_BY_GRANTOR = "PERMISSION_NOT_HELD_BY_GRANTOR";

    /** ADR 0017 guardrail 2: the last holder of user administration cannot be removed. */
    public static final String LAST_ADMINISTRATOR = "LAST_ADMINISTRATOR";

    /** A required field was absent or blank. {@code reason} names it. */
    public static final String FIELD_REQUIRED = "FIELD_REQUIRED";

    /** A field was present but unusable — a malformed date, say. {@code reason} names it. */
    public static final String FIELD_INVALID = "FIELD_INVALID";

    /** ADR 0017 guardrail 0. {@code reason} ∈ COLLIDES_WITH_PERMISSION, RESERVED_PREFIX, MALFORMED. */
    public static final String ROLE_NAME_INVALID = "ROLE_NAME_INVALID";

    /** A role by that name already exists for this tenant. */
    public static final String ROLE_EXISTS = "ROLE_EXISTS";

    /** Refused: the role is still granted to somebody. {@code details.holders}. */
    public static final String ROLE_IN_USE = "ROLE_IN_USE";

    /** Refused: a platform template is the starting position, not the tenant's to delete. */
    public static final String ROLE_NOT_CUSTOM = "ROLE_NOT_CUSTOM";

    /** The institution already has that job title, in some spelling. */
    public static final String JOB_TITLE_EXISTS = "JOB_TITLE_EXISTS";

    /**
     * The job title is not in this institution's vocabulary.
     *
     * <p>Refused rather than accepted as free text: a title nobody authored is how "Teller",
     * "teller" and "Cashier/Teller" become three jobs, and a vocabulary that anything can be added
     * to by typing is not a vocabulary.
     */
    public static final String JOB_TITLE_UNKNOWN = "JOB_TITLE_UNKNOWN";

    /** Refused: somebody still holds that title. {@code details.holders}. */
    public static final String JOB_TITLE_IN_USE = "JOB_TITLE_IN_USE";

    /** That staff number belongs to somebody else. The unique index arbitrates, never a pre-check. */
    public static final String STAFF_NUMBER_TAKEN = "STAFF_NUMBER_TAKEN";
}
