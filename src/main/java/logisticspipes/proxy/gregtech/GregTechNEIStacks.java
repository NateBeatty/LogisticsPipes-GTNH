package logisticspipes.proxy.gregtech;

import codechicken.nei.PositionedStack;
import gregtech.nei.GTNEIDefaultHandler;

/**
 * Reads what GregTech's NEI recipe stacks know beyond an item and a position.
 * <p>
 * Kept in its own class so nothing here is loaded unless GregTech is present, and only used from the NEI overlay
 * handler, i.e. on the client.
 */
public final class GregTechNEIStacks {

    private GregTechNEIStacks() {}

    /**
     * Whether GregTech marks this ingredient "NC", or null if it isn't one of GregTech's stacks (or this GregTech
     * version can't say). GregTech's handler may draw every stack as size 1, so its own flag is the reliable one.
     */
    public static Boolean isNotConsumed(PositionedStack stack) {
        try {
            if (stack instanceof GTNEIDefaultHandler.FixedPositionedStack) {
                return ((GTNEIDefaultHandler.FixedPositionedStack) stack).isNotConsumed();
            }
        } catch (LinkageError e) {
            // An older GregTech without the flag; the caller falls back to the stack size.
        }
        return null;
    }
}
