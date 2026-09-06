package com.piglinmine.jeioptimizer;

/**
 * Marks the one fake menu JEI is currently driving, so its redundant recipe
 * recalculations can be skipped.
 * <p>
 * Holds the menu instance rather than a boolean on purpose: if a throw ever skips
 * the reset, only JEI's own fake menu keeps suppressing — a real player menu is
 * never affected.
 */
public final class MenuUpdateGuard {
    private MenuUpdateGuard() {}

    private static volatile Object active;

    public static void begin(Object menu) {
        if (Config.SKIP_REDUNDANT_MENU_UPDATES) active = menu;
    }

    public static void end() {
        active = null;
    }

    public static boolean isSuppressed(Object menu) {
        return Config.SKIP_REDUNDANT_MENU_UPDATES && active == menu;
    }
}
