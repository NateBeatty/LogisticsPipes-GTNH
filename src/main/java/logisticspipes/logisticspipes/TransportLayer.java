/*
 * Copyright (c) Krapht, 2011 "LogisticsPipes" is distributed under the terms of the Minecraft Mod Public License 1.0,
 * or MMPL. Please check the contents of the license located in http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package logisticspipes.logisticspipes;

import net.minecraftforge.common.util.ForgeDirection;

/**
 * This class is responsible for handling items arriving at its destination
 *
 * @author Krapht
 */
public abstract class TransportLayer {

    public abstract boolean stillWantItem(IRoutedItem item);

    /**
     * Like {@link #stillWantItem}, but for active deliveries (items sent for an order), which are normally always
     * accepted. Returning false sends the item on to another destination.
     */
    public boolean acceptsActiveItem(IRoutedItem item) {
        return true;
    }

    public abstract ForgeDirection itemArrived(IRoutedItem item, ForgeDirection denyed);

    public void handleItem(IRoutedItem item) {}
}
