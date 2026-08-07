package org.elyonar.fincore.notification.internal.channel;

/**
 * How many units a rendered message costs on a channel.
 *
 * <p>This exists because PRD §4.9 asks for Hausa, Yoruba and Igbo templates in the same breath as
 * per-tenant cost reporting, and those two collide. GSM-7 encodes 160 characters per SMS segment;
 * anything outside its alphabet forces UCS-2, which encodes 70. So a template that fits in one
 * segment in English can cost three in Yoruba — and the first place anyone notices is the bill.
 *
 * <p>Counted at publish time as well as at send time, so a template over its channel's cap is
 * refused while someone is still editing it.
 */
public final class Segments {

    private Segments() {}

    private static final int GSM7_SINGLE = 160;
    private static final int GSM7_CONCATENATED = 153;
    private static final int UCS2_SINGLE = 70;
    private static final int UCS2_CONCATENATED = 67;

    /**
     * The GSM 03.38 basic alphabet, plus the extension characters that cost two septets each.
     *
     * <p>Written out rather than approximated by an ASCII range: {@code $}, {@code @} and the
     * European letters are in it, while {@code [} and {@code ]} cost double, and a range check
     * would be wrong in both directions.
     */
    private static final String GSM7_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
                    + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    private static final String GSM7_EXTENDED = "^{}\\[~]|€";

    public static int unitsFor(Channels.ContentModel model, String text) {
        return switch (model) {
            case PLAIN -> 1;
            case SEGMENTED -> smsSegments(text);
        };
    }

    private static int smsSegments(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        if (isGsm7(text)) {
            int septets = gsm7Length(text);
            return septets <= GSM7_SINGLE ? 1 : ceilDiv(septets, GSM7_CONCATENATED);
        }
        // UCS-2 counts UTF-16 code units, so an emoji or a rare CJK character outside the basic
        // multilingual plane costs two. Using code points here would undercount them.
        int units = text.length();
        return units <= UCS2_SINGLE ? 1 : ceilDiv(units, UCS2_CONCATENATED);
    }

    public static boolean isGsm7(String text) {
        return text.codePoints().allMatch(cp -> GSM7_BASIC.indexOf(cp) >= 0 || GSM7_EXTENDED.indexOf(cp) >= 0);
    }

    private static int gsm7Length(String text) {
        int septets = 0;
        for (int i = 0; i < text.length(); i++) {
            septets += GSM7_EXTENDED.indexOf(text.charAt(i)) >= 0 ? 2 : 1;
        }
        return septets;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
