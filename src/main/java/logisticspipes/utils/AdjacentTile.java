package logisticspipes.utils;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import logisticspipes.pipes.basic.CoreRoutedPipe;

public class AdjacentTile {

    public TileEntity tile;
    public ForgeDirection orientation;

    /**
     * Face to send results extracted from this tile, relative to the pipe that sends them. Defaults to
     * {@link #orientation}: for the machine the chassis faces, the extract face and the send face are the same.
     */
    public ForgeDirection sendOrientation;

    /**
     * The pipe results extracted from this tile enter the network through, or null for the pipe that extracted them.
     * Set when the tile is not next to that pipe (a Smart Satellite's machine), so the result comes out of the pipe the
     * machine is actually attached to.
     */
    public CoreRoutedPipe sender;

    public AdjacentTile(TileEntity tile, ForgeDirection orientation) {
        this.tile = tile;
        this.orientation = orientation;
    }

    public ForgeDirection getSendOrientation() {
        return sendOrientation != null ? sendOrientation : orientation;
    }
}
