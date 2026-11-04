package com.piglinmine.jeioptimizer;

/**
 * Marks the one fake menu JEI is currently driving, so its redundant recipe
 * recalculations can be skipped.
 * <p>
 * Holds the menu instance rather than a boolean on purpose: if a throw ever skips
 * the reset, only JEI's own fake menu keeps suppressing - a real player menu is
 * never affected.
 */
public final class MenuUpdateGuard {
    private MenuUpdateGuard() {}

    private static volatile Object active;

    private static volatile Object pendingLeft;
    private static volatile Object pendingRight;

    public static void begin(Object menu) {
        if (Config.SKIP_REDUNDANT_MENU_UPDATES) active = menu;
    }

    public static void begin(Object menu, Object left, Object right) {
        pendingLeft = left;
        pendingRight = right;
        begin(menu);
    }

    public static Object pendingLeft() {
        return pendingLeft;
    }

    public static Object pendingRight() {
        return pendingRight;
    }

    public static void end() {
        active = null;
    }

    public static boolean isSuppressed(Object menu) {
        return Config.SKIP_REDUNDANT_MENU_UPDATES && active == menu;
    }
}
