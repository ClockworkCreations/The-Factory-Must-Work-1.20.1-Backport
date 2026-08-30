package com.tfmgtweaks.compat;

/**
 * Detects which TFMG build is running: original DrMangoTea vs. the
 * Community Edition fork. Structural, not version-string based: CE
 * moved its JEI integration to a package that only exists there, so
 * probing for it is a reliable signal that doesn't depend on parsing a
 * version string. initialize = false so this never triggers
 * &lt;clinit&gt; on either side.
 */
public enum TFMGEdition {
    VANILLA,
    COMMUNITY_EDITION;

    private static final String CE_MARKER_CLASS = "com.drmangotea.tfmg.integration.jei.TFMGJei";

    private static TFMGEdition detected;

    public static TFMGEdition current() {
        if (detected == null) {
            detected = probe();
        }
        return detected;
    }

    private static TFMGEdition probe() {
        try {
            Class.forName(CE_MARKER_CLASS, false, TFMGEdition.class.getClassLoader());
            return COMMUNITY_EDITION;
        } catch (ClassNotFoundException e) {
            return VANILLA;
        }
    }

    public static boolean isCommunityEdition() {
        return current() == COMMUNITY_EDITION;
    }

    public static boolean isVanilla() {
        return current() == VANILLA;
    }
}
