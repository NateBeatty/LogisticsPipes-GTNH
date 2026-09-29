package logisticspipes.renderer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Outlines blocks through walls for a while, for the request table's Locate button. Modelled on AE2's
 * BlockPosHighlighter: each call replaces the last highlight, and it lasts 500 ms per block of taxicab distance to the
 * farthest block, clamped to 3-30 s. The time only runs while no GUI is open, so it isn't used up behind the request
 * table.
 */
public class LocateHighlighter {

    private static final long MIN_TIME = 3000;
    private static final long MAX_TIME = 30000;
    private static final long TIME_PER_BLOCK = 500;
    private static final double GROW = 0.002;

    public static final int PIPE_COLOR = 0x4DE6FF;
    public static final int MACHINE_COLOR = 0xFFCC33;

    public static final LocateHighlighter INSTANCE = new LocateHighlighter();

    private static final class Target {

        final int x, y, z, color;

        Target(int x, int y, int z, int color) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
        }
    }

    private final List<Target> targets = new ArrayList<>();
    private int dimension;
    private long remaining;
    private long lastFrame;

    private LocateHighlighter() {}

    /** Starts a new highlight in the given dimension; call {@link #add} for each block, then {@link #start}. */
    public void clear() {
        targets.clear();
        remaining = 0;
    }

    public void add(int x, int y, int z, int color) {
        targets.add(new Target(x, y, z, color));
    }

    public void start(int dimension) {
        this.dimension = dimension;
        Minecraft mc = Minecraft.getMinecraft();
        long farthest = 0;
        if (mc.thePlayer != null) {
            for (Target t : targets) {
                long distance = Math.abs(t.x - (long) Math.floor(mc.thePlayer.posX))
                        + Math.abs(t.y - (long) Math.floor(mc.thePlayer.posY))
                        + Math.abs(t.z - (long) Math.floor(mc.thePlayer.posZ));
                farthest = Math.max(farthest, distance);
            }
        }
        remaining = Math.max(MIN_TIME, Math.min(MAX_TIME, farthest * TIME_PER_BLOCK));
        lastFrame = System.currentTimeMillis();
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (targets.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            clear();
            return;
        }
        long now = System.currentTimeMillis();
        if (mc.currentScreen == null) {
            remaining -= now - lastFrame;
        }
        lastFrame = now;
        if (remaining <= 0) {
            clear();
            return;
        }
        if (mc.theWorld.provider.dimensionId != dimension || now % 1000 >= 700) {
            return; // other dimension, or the "off" part of the blink
        }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(3.0F);
        double camX = RenderManager.renderPosX;
        double camY = RenderManager.renderPosY;
        double camZ = RenderManager.renderPosZ;
        for (Target t : targets) {
            GL11.glColor4f(
                    ((t.color >> 16) & 0xFF) / 255F,
                    ((t.color >> 8) & 0xFF) / 255F,
                    (t.color & 0xFF) / 255F,
                    1.0F);
            RenderGlobal.drawOutlinedBoundingBox(
                    AxisAlignedBB.getBoundingBox(
                            t.x - camX - GROW,
                            t.y - camY - GROW,
                            t.z - camZ - GROW,
                            t.x + 1 - camX + GROW,
                            t.y + 1 - camY + GROW,
                            t.z + 1 - camZ + GROW),
                    -1);
        }
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }
}
