package logisticspipes.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import cpw.mods.fml.common.registry.GameRegistry;
import logisticspipes.blocks.stats.LogisticsStatisticsTileEntity;
import logisticspipes.blocks.stats.TrackingTask;
import logisticspipes.gui.popup.GuiAddTracking;
import logisticspipes.gui.popup.GuiConfirmPopup;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.packets.block.CancelCraftingJobPacket;
import logisticspipes.network.packets.block.RemoveAmoundTask;
import logisticspipes.network.packets.block.RequestAmountTaskSubGui;
import logisticspipes.network.packets.block.RequestCraftingJobs;
import logisticspipes.network.packets.block.RequestRunningCraftingTasks;
import logisticspipes.proxy.MainProxy;
import logisticspipes.routing.order.CraftingJob;
import logisticspipes.routing.order.CraftingJobInfo;
import logisticspipes.routing.order.CraftingJobs;
import logisticspipes.routing.order.IOrderInfoProvider.ResourceType;
import logisticspipes.utils.Color;
import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.ItemDisplay;
import logisticspipes.utils.gui.LogisticsBaseGuiScreen;
import logisticspipes.utils.gui.SmallGuiButton;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.string.StringUtils;
import logisticspipes.utils.tuples.LPPosition;

public class GuiStatistics extends LogisticsBaseGuiScreen {

    private final String PREFIX = "gui.networkstatistics.";

    private final int TAB_COUNT = 3;
    private int current_Tab;

    private final List<GuiButton> TAB_BUTTON_1 = new ArrayList<>();
    private final List<GuiButton> TAB_BUTTON_1_2 = new ArrayList<>();
    private final List<GuiButton> TAB_BUTTON_2 = new ArrayList<>();

    /* Third tab: crafting jobs */

    private static final int JOBS_TAB = 2;
    private static final int JOB_ROWS = 8;
    private static final int JOB_ROW_HEIGHT = 20;
    private static final int WAIT_ROWS = 7;
    private static final int WAIT_ROW_HEIGHT = 18;
    /** Jobs that ended stay listed, greyed, until the table is closed; at most this many. */
    private static final int MAX_ENDED_ROWS = 10;
    /** How often the list is refreshed while the tab is open, in client ticks. */
    private static final int REFRESH_TICKS = 20;
    /** A job idle this long is drawn in red, to stand out as possibly stuck. */
    private static final long STALLED_TICKS = 20 * 60 * 2;

    private enum JobSort {

        OLDEST("Oldest first"),
        NEWEST("Newest first"),
        IDLE("Most idle");

        final String label;

        JobSort(String label) {
            this.label = label;
        }

        JobSort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** A job as last reported, plus how it ended once it did. */
    private static final class JobRow {

        CraftingJobInfo info;
        CraftingJob.End ended;
        long endedOrder;

        JobRow(CraftingJobInfo info) {
            this.info = info;
        }
    }

    private final List<JobRow> jobRows = new ArrayList<>();
    private JobSort jobSort = JobSort.OLDEST;
    private int jobPage = 0;
    private long endedCounter = 0;
    /** The job whose open orders are shown, or -1 for the list. */
    private int detailJobId = -1;
    private List<CraftingJob.WaitingOn> detail = new ArrayList<>();
    private int detailPage = 0;
    private int refreshIn = 0;

    private final List<GuiButton> TAB_BUTTON_3_LIST = new ArrayList<>();
    private final List<GuiButton> TAB_BUTTON_3_DETAIL = new ArrayList<>();
    private final List<GuiButton> TAB_BUTTON_3_PAGES = new ArrayList<>();
    private final GuiButton[] jobCancelButtons = new GuiButton[JOB_ROWS];
    private GuiButton sortButton;
    private GuiButton detailCancelButton;
    private final LogisticsStatisticsTileEntity tile;

    private ItemDisplay itemDisplay_1;
    private ItemDisplay itemDisplay_2;

    private int move_left;

    public GuiStatistics(final LogisticsStatisticsTileEntity tile) {
        super(256, 220, 0, 0);
        this.tile = tile;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        TAB_BUTTON_1.add(addButton(new GuiButton(0, guiLeft + 10, guiTop + 70, 20, 20, "<")));
        TAB_BUTTON_1.add(addButton(new GuiButton(1, guiLeft + xSize - 30, guiTop + 70, 20, 20, ">")));
        TAB_BUTTON_1.add(addButton(new GuiButton(2, guiLeft + xSize / 2 - 53, guiTop + 70, 40, 20, "Add")));
        TAB_BUTTON_1.add(addButton(new GuiButton(3, guiLeft + xSize / 2 - 7, guiTop + 70, 60, 20, "Remove")));
        TAB_BUTTON_1_2.add(addButton(new SmallGuiButton(4, guiLeft + xSize / 2 - 6, guiTop + 205, 10, 10, "<")));
        TAB_BUTTON_1_2.add(addButton(new SmallGuiButton(5, guiLeft + xSize / 2 + 6, guiTop + 205, 10, 10, ">")));
        TAB_BUTTON_2.add(
                addButton(
                        new GuiButton(
                                6,
                                guiLeft + 10,
                                guiTop + 40,
                                xSize - 20,
                                20,
                                StringUtils.translate(PREFIX + "gettasks"))));
        TAB_BUTTON_2.add(addButton(new SmallGuiButton(7, guiLeft + xSize - 90, guiTop + 65, 10, 10, "<")));
        TAB_BUTTON_2.add(addButton(new SmallGuiButton(8, guiLeft + xSize - 20, guiTop + 65, 10, 10, ">")));

        TAB_BUTTON_3_LIST.clear();
        TAB_BUTTON_3_DETAIL.clear();
        TAB_BUTTON_3_PAGES.clear();
        sortButton = addButton(new SmallGuiButton(9, guiLeft + xSize - 74, guiTop + 26, 64, 10, jobSort.label));
        TAB_BUTTON_3_LIST.add(sortButton);
        TAB_BUTTON_3_PAGES.add(addButton(new SmallGuiButton(10, guiLeft + xSize - 60, guiTop + 205, 10, 10, "<")));
        TAB_BUTTON_3_PAGES.add(addButton(new SmallGuiButton(11, guiLeft + xSize - 20, guiTop + 205, 10, 10, ">")));
        TAB_BUTTON_3_DETAIL.add(addButton(new SmallGuiButton(12, guiLeft + 10, guiTop + 205, 30, 10, "Back")));
        detailCancelButton = addButton(new SmallGuiButton(13, guiLeft + 45, guiTop + 205, 60, 10, "Cancel"));
        TAB_BUTTON_3_DETAIL.add(detailCancelButton);
        for (int i = 0; i < JOB_ROWS; i++) {
            jobCancelButtons[i] = addButton(
                    new SmallGuiButton(20 + i, guiLeft + xSize - 22, guiTop + 45 + i * JOB_ROW_HEIGHT, 10, 10, "x"));
        }

        if (itemDisplay_1 == null) {
            itemDisplay_1 = new ItemDisplay(
                    null,
                    fontRendererObj,
                    this,
                    null,
                    guiLeft + 10,
                    guiTop + 18,
                    xSize - 20,
                    ySize - 100,
                    new int[] { 1, 10, 64, 64 },
                    true);
        }
        itemDisplay_1.reposition(guiLeft + 10, guiTop + 40, xSize - 20, 20);

        if (itemDisplay_2 == null) {
            itemDisplay_2 = new ItemDisplay(
                    null,
                    fontRendererObj,
                    this,
                    null,
                    guiLeft + 10,
                    guiTop + 18,
                    xSize - 20,
                    ySize - 100,
                    new int[] { 1, 10, 64, 64 },
                    true);
            itemDisplay_2.setItemList(new ArrayList<>());
        }
        itemDisplay_2.reposition(guiLeft + 10, guiTop + 80, xSize - 20, 125);

        updateItemList();
    }

    @Override
    public void resetSubGui() {
        super.resetSubGui();
        updateItemList();
    }

    public void updateItemList() {
        List<ItemIdentifierStack> allItems = new ArrayList<>();
        for (TrackingTask task : tile.tasks) {
            allItems.add(task.item.makeStack(1));
        }
        itemDisplay_1.setItemList(allItems);
    }

    @Override
    protected void actionPerformed(GuiButton p_146284_1_) {
        if (p_146284_1_.id == 0) {
            itemDisplay_1.prevPage();
        } else if (p_146284_1_.id == 1) {
            itemDisplay_1.nextPage();
        } else if (p_146284_1_.id == 2) {
            MainProxy.sendPacketToServer(PacketHandler.getPacket(RequestAmountTaskSubGui.class).setTilePos(tile));
        } else if (p_146284_1_.id == 3 && itemDisplay_1.getSelectedItem() != null) {
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(RemoveAmoundTask.class).setItem(itemDisplay_1.getSelectedItem().getItem())
                            .setTilePos(tile));
            Iterator<TrackingTask> iter = tile.tasks.iterator();
            while (iter.hasNext()) {
                TrackingTask task = iter.next();
                if (task.item == itemDisplay_1.getSelectedItem().getItem()) {
                    iter.remove();
                    break;
                }
            }
            updateItemList();
        } else if (p_146284_1_.id == 4) {
            move_left++;
            if (move_left >= 24 * 4) {
                move_left--;
            }
        } else if (p_146284_1_.id == 5) {
            move_left--;
            if (move_left < 0) {
                move_left = 0;
            }
        } else if (p_146284_1_.id == 6) {
            MainProxy.sendPacketToServer(PacketHandler.getPacket(RequestRunningCraftingTasks.class).setTilePos(tile));
        } else if (p_146284_1_.id == 7) {
            itemDisplay_2.prevPage();
        } else if (p_146284_1_.id == 8) {
            itemDisplay_2.nextPage();
        } else if (p_146284_1_.id == 9) {
            jobSort = jobSort.next();
            sortButton.displayString = jobSort.label;
            jobPage = 0;
        } else if (p_146284_1_.id == 10 || p_146284_1_.id == 11) {
            turnJobPage(p_146284_1_.id == 11 ? 1 : -1);
        } else if (p_146284_1_.id == 12) {
            showJobList();
        } else if (p_146284_1_.id == 13) {
            JobRow row = findRow(detailJobId);
            if (row != null && row.ended == null) {
                confirmCancel(row.info);
            }
        } else if (p_146284_1_.id >= 20 && p_146284_1_.id < 20 + JOB_ROWS) {
            List<JobRow> rows = sortedRows();
            int index = jobPage * JOB_ROWS + (p_146284_1_.id - 20);
            if (index < rows.size() && rows.get(index).ended == null) {
                confirmCancel(rows.get(index).info);
            }
        }
    }

    /* Crafting requests tab */

    private static ItemStack requestsTabIcon;

    /**
     * IC2's electronic circuit when IC2 is installed, redstone dust otherwise. Looked up by registry name, so there is
     * no dependency on IC2.
     */
    private static ItemStack requestsTabIcon() {
        if (requestsTabIcon == null) {
            Item circuit = GameRegistry.findItem("IC2", "itemPartCircuit");
            requestsTabIcon = new ItemStack(circuit != null ? circuit : Items.redstone);
        }
        return requestsTabIcon;
    }

    /**
     * Asks the server for the job list about once a second, only while this tab is showing. Nothing is sent or kept up
     * to date for a table nobody is looking at.
     */
    @Override
    public void updateScreen() {
        super.updateScreen();
        if (current_Tab != JOBS_TAB) {
            return;
        }
        if (--refreshIn <= 0) {
            refreshIn = REFRESH_TICKS;
            MainProxy.sendPacketToServer(
                    PacketHandler.getPacket(RequestCraftingJobs.class).setInteger(detailJobId).setTilePos(tile));
        }
    }

    public void handleJobList(List<CraftingJobInfo> jobs, List<CraftingJobs.Ended> ended, int forDetail,
            List<CraftingJob.WaitingOn> detailList) {
        Set<Integer> live = new HashSet<>();
        for (CraftingJobInfo info : jobs) {
            live.add(info.getId());
            JobRow row = findRow(info.getId());
            if (row == null) {
                jobRows.add(new JobRow(info));
            } else {
                row.info = info;
                row.ended = null;
            }
        }
        for (JobRow row : jobRows) {
            if (row.ended != null || live.contains(row.info.getId())) {
                continue;
            }
            // Gone from the live list: the server says how it ended if it was recent enough, otherwise it finished.
            row.ended = CraftingJob.End.FINISHED;
            for (CraftingJobs.Ended e : ended) {
                if (e.id == row.info.getId()) {
                    row.ended = e.end;
                    if (e.finalInfo != null) {
                        // Its state at the moment it finished, not the last refresh before that.
                        row.info = e.finalInfo;
                    }
                }
            }
            row.endedOrder = ++endedCounter;
        }
        dropOldEndedRows();
        if (forDetail == detailJobId && detailJobId != -1) {
            detail = detailList;
        }
    }

    private void dropOldEndedRows() {
        List<JobRow> endedRows = new ArrayList<>();
        for (JobRow row : jobRows) {
            if (row.ended != null) {
                endedRows.add(row);
            }
        }
        if (endedRows.size() <= MAX_ENDED_ROWS) {
            return;
        }
        endedRows.sort(Comparator.comparingLong(r -> r.endedOrder));
        jobRows.removeAll(endedRows.subList(0, endedRows.size() - MAX_ENDED_ROWS));
    }

    private JobRow findRow(int id) {
        for (JobRow row : jobRows) {
            if (row.info.getId() == id) {
                return row;
            }
        }
        return null;
    }

    /** Live jobs in the chosen order, then the ended ones, most recently ended first. */
    private List<JobRow> sortedRows() {
        List<JobRow> liveRows = new ArrayList<>();
        List<JobRow> endedRows = new ArrayList<>();
        for (JobRow row : jobRows) {
            (row.ended == null ? liveRows : endedRows).add(row);
        }
        switch (jobSort) {
            case NEWEST:
                liveRows.sort(Comparator.comparingLong(r -> r.info.getQueuedTicksAgo()));
                break;
            case IDLE:
                liveRows.sort((a, b) -> Long.compare(b.info.getIdleTicks(), a.info.getIdleTicks()));
                break;
            default:
                liveRows.sort((a, b) -> Long.compare(b.info.getQueuedTicksAgo(), a.info.getQueuedTicksAgo()));
        }
        endedRows.sort((a, b) -> Long.compare(b.endedOrder, a.endedOrder));
        liveRows.addAll(endedRows);
        return liveRows;
    }

    private void showJobList() {
        detailJobId = -1;
        detail = new ArrayList<>();
        refreshIn = 0;
    }

    private void showJobDetail(int jobId) {
        detailJobId = jobId;
        detail = new ArrayList<>();
        detailPage = 0;
        refreshIn = 0; // ask for its orders now rather than in up to a second
    }

    private void turnJobPage(int by) {
        int size = detailJobId == -1 ? sortedRows().size() : detail.size();
        int perPage = detailJobId == -1 ? JOB_ROWS : WAIT_ROWS;
        int pages = Math.max(1, (size + perPage - 1) / perPage);
        if (detailJobId == -1) {
            jobPage = Math.floorMod(jobPage + by, pages);
        } else {
            detailPage = Math.floorMod(detailPage + by, pages);
        }
    }

    private void confirmCancel(CraftingJobInfo info) {
        int jobId = info.getId();
        setSubGui(
                new GuiConfirmPopup(
                        "Cancel",
                        () -> MainProxy.sendPacketToServer(
                                PacketHandler.getPacket(CancelCraftingJobPacket.class).setInteger(jobId)
                                        .setTilePos(tile)),
                        "Cancel " + jobName(info) + "?",
                        "Stops " + info.getOpenCrafts()
                                + " craft(s) and "
                                + info.getOpenDeliveries()
                                + " delivery(ies).",
                        "Items already moving will still arrive.",
                        "This can't be undone."));
    }

    private static String jobName(CraftingJobInfo info) {
        ItemIdentifierStack main = info.getMainItem();
        String name = main == null ? "Request #" + info.getId() : main.getFriendlyName();
        int more = info.getRequested().size() - 1;
        return more > 0 ? name + " +" + more + " more" : name;
    }

    /** Ticks as "45s", "14m" or "2h5m". */
    private static String age(long ticks) {
        long seconds = Math.max(0, ticks / 20);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        return (minutes / 60) + "h" + (minutes % 60) + "m";
    }

    private static String where(LPPosition position) {
        return position == null ? "?" : position.getX() + ", " + position.getY() + ", " + position.getZ();
    }

    private void renderItem(ItemIdentifierStack stack, int x, int y) {
        if (stack == null) {
            return;
        }
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderHelper.enableGUIStandardItemLighting();
        GuiScreen.itemRender
                .renderItemAndEffectIntoGUI(fontRendererObj, getMC().renderEngine, stack.makeNormalStack(), x, y);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GuiScreen.itemRender.zLevel = 0.0F;
    }

    /** Whether the mouse is on the request shown at the top of the detail view, which collapses it when clicked. */
    private boolean isOverDetailHeader(int mouseX, int mouseY) {
        int x = guiLeft + 10;
        int top = guiTop + 40;
        return detailJobId != -1 && mouseX >= x
                && mouseX < x + rowWidth()
                && mouseY >= top
                && mouseY < top + JOB_ROW_HEIGHT;
    }

    /** Width of a request row, leaving room on the right for its cancel button. */
    private int rowWidth() {
        return xSize - 34;
    }

    private void drawJobRow(JobRow row, int x, int y, boolean hovered) {
        if (hovered) {
            drawRect(x, y, x + rowWidth(), y + JOB_ROW_HEIGHT - 1, Color.LIGHTER_GREY);
        }
        CraftingJobInfo info = row.info;
        renderItem(info.getMainItem(), x + 1, y + 2);
        int textColor = row.ended != null ? 0x909090 : 0x404040;
        // Crafted over the whole request, right-aligned on the name's line, like the per-item counts in the detail
        // view.
        int nameWidth = rowWidth() - 22;
        if (info.getCraftedRequired() > 0) {
            String crafted = info.getCraftedDone() + "/" + info.getCraftedRequired() + " crafted";
            int craftedWidth = fontRendererObj.getStringWidth(crafted);
            fontRendererObj.drawString(crafted, x + rowWidth() - 2 - craftedWidth, y + 1, textColor);
            nameWidth -= craftedWidth + 6;
        }
        fontRendererObj.drawString(
                StringUtils.getWithMaxWidth(jobName(info), nameWidth, fontRendererObj),
                x + 20,
                y + 1,
                textColor);
        String status;
        int statusColor = textColor;
        if (row.ended != null) {
            status = row.ended == CraftingJob.End.CANCELLED ? "Cancelled" : "Done";
        } else {
            status = age(info.getQueuedTicksAgo()) + " ago | idle "
                    + age(info.getIdleTicks())
                    + " | "
                    + info.getSent()
                    + "/"
                    + info.getTotal();
            if (info.isReplanShort()) {
                status = "SHORT | " + status;
            }
            if (info.isReplanShort() || info.getIdleTicks() >= STALLED_TICKS) {
                statusColor = 0xAA0000;
            }
        }
        fontRendererObj.drawString(
                StringUtils.getWithMaxWidth(status, rowWidth() - 22, fontRendererObj),
                x + 20,
                y + 10,
                statusColor);
    }

    private void drawJobsTab(int mouseX, int mouseY) {
        int x = guiLeft + 10;
        int top = guiTop + 40;
        if (detailJobId == -1) {
            List<JobRow> rows = sortedRows();
            if (rows.isEmpty()) {
                String empty = "No crafting requests on this network.";
                fontRendererObj
                        .drawString(empty, xCenter - fontRendererObj.getStringWidth(empty) / 2, top + 20, 0x404040);
                return;
            }
            int pages = Math.max(1, (rows.size() + JOB_ROWS - 1) / JOB_ROWS);
            jobPage = Math.min(jobPage, pages - 1);
            for (int i = 0; i < JOB_ROWS; i++) {
                int index = jobPage * JOB_ROWS + i;
                if (index >= rows.size()) {
                    break;
                }
                int y = top + i * JOB_ROW_HEIGHT;
                boolean hovered = mouseX >= x && mouseX < x + rowWidth() && mouseY >= y && mouseY < y + JOB_ROW_HEIGHT;
                drawJobRow(rows.get(index), x, y, hovered && !hasSubGui());
            }
            drawPageNumber(jobPage, pages);
            return;
        }
        JobRow row = findRow(detailJobId);
        if (row == null) {
            showJobList();
            return;
        }
        drawJobRow(row, x, top, isOverDetailHeader(mouseX, mouseY) && !hasSubGui());
        drawRect(x, top + JOB_ROW_HEIGHT + 1, x + xSize - 20, top + JOB_ROW_HEIGHT + 2, Color.DARKER_GREY);
        int waitTop = top + JOB_ROW_HEIGHT + 4;
        if (row.ended != null || detail.isEmpty()) {
            String none = row.ended != null ? "This job has ended." : "Waiting for the server...";
            fontRendererObj.drawString(none, x + 2, waitTop + 4, 0x404040);
            return;
        }
        int pages = Math.max(1, (detail.size() + WAIT_ROWS - 1) / WAIT_ROWS);
        detailPage = Math.min(detailPage, pages - 1);
        for (int i = 0; i < WAIT_ROWS; i++) {
            int index = detailPage * WAIT_ROWS + i;
            if (index >= detail.size()) {
                break;
            }
            CraftingJob.WaitingOn waiting = detail.get(index);
            int y = waitTop + i * WAIT_ROW_HEIGHT;
            renderItem(waiting.getItem(), x + 1, y + 1);
            int textWidth = xSize - 40;
            // Progress on the right of the first line, the item name in what is left of it.
            String progress = waiting.getDone() + "/"
                    + waiting.getRequired()
                    + (waiting.getType() == ResourceType.CRAFTING ? " crafted" : " delivered");
            int progressWidth = fontRendererObj.getStringWidth(progress);
            fontRendererObj.drawString(progress, x + 20 + textWidth - progressWidth, y, 0x404040);
            String what = waiting.getItem() == null ? "?" : waiting.getItem().getItem().getFriendlyName();
            fontRendererObj.drawString(
                    StringUtils.getWithMaxWidth(what, textWidth - progressWidth - 6, fontRendererObj),
                    x + 20,
                    y,
                    0x404040);
            String owner = waiting.getType() == ResourceType.CRAFTING ? "Crafter"
                    : waiting.getType() == ResourceType.PROVIDER ? "Provider" : "Surplus";
            String line = owner + " " + where(waiting.getPosition()) + " | idle " + age(waiting.getIdleTicks());
            int color = waiting.getIdleTicks() >= STALLED_TICKS ? 0xAA0000 : 0x606060;
            fontRendererObj
                    .drawString(StringUtils.getWithMaxWidth(line, textWidth, fontRendererObj), x + 20, y + 9, color);
        }
        drawPageNumber(detailPage, pages);
    }

    private void drawPageNumber(int page, int pages) {
        String text = (page + 1) + "/" + pages;
        fontRendererObj.drawString(
                text,
                guiLeft + xSize - 35 - fontRendererObj.getStringWidth(text) / 2,
                guiTop + 206,
                0x404040);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float f, int mouse_x, int mouse_y) {
        GL11.glColor4d(1.0D, 1.0D, 1.0D, 1.0D);
        for (int i = 0; i < TAB_COUNT; i++) {
            GuiGraphics.drawGuiBackGround(
                    mc,
                    guiLeft + (25 * i) + 2,
                    guiTop - 2,
                    guiLeft + 27 + (25 * i),
                    guiTop + 35,
                    zLevel,
                    false,
                    true,
                    true,
                    false,
                    true);
        }
        GuiGraphics.drawGuiBackGround(mc, guiLeft, guiTop + 20, right, bottom, zLevel, true);
        GuiGraphics.drawGuiBackGround(
                mc,
                guiLeft + (25 * current_Tab) + 2,
                guiTop - 2,
                guiLeft + 27 + (25 * current_Tab),
                guiTop + 38,
                zLevel,
                true,
                true,
                true,
                false,
                true);

        // First Tab
        GuiGraphics.drawStatsBackground(mc, guiLeft + 6, guiTop + 3);

        // Second Tab
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderHelper.enableGUIStandardItemLighting();
        ItemStack stack = new ItemStack(Blocks.crafting_table, 0);
        GuiScreen.itemRender
                .renderItemAndEffectIntoGUI(fontRendererObj, getMC().renderEngine, stack, guiLeft + 31, guiTop + 3);
        // Third Tab
        GuiScreen.itemRender.renderItemAndEffectIntoGUI(
                fontRendererObj,
                getMC().renderEngine,
                requestsTabIcon(),
                guiLeft + 56,
                guiTop + 3);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GuiScreen.itemRender.zLevel = 0.0F;

        if (current_Tab == 0) {
            itemDisplay_1.renderItemArea(zLevel);
            itemDisplay_1.renderPageNumber(right - 40, guiTop + 28);
            if (itemDisplay_1.getSelectedItem() != null) {
                TrackingTask task = null;
                for (TrackingTask taskLoop : tile.tasks) {
                    if (taskLoop.item == itemDisplay_1.getSelectedItem().getItem()) {
                        task = taskLoop;
                        break;
                    }
                }
                if (task != null) {
                    GuiGraphics.drawSlotBackground(mc, guiLeft + 10, guiTop + 99);
                    GL11.glEnable(GL12.GL_RESCALE_NORMAL);
                    OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
                    GL11.glEnable(GL11.GL_LIGHTING);
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    RenderHelper.enableGUIStandardItemLighting();
                    GuiScreen.itemRender.renderItemAndEffectIntoGUI(
                            fontRendererObj,
                            getMC().renderEngine,
                            task.item.makeNormalStack(1),
                            guiLeft + 11,
                            guiTop + 100);
                    GL11.glDisable(GL11.GL_LIGHTING);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    GuiScreen.itemRender.zLevel = 0.0F;
                    mc.fontRenderer.drawString(
                            StringUtils.getWithMaxWidth(task.item.getFriendlyName(), xSize - 44, fontRendererObj),
                            guiLeft + 32,
                            guiTop + 104,
                            Color.getValue(Color.DARKER_GREY),
                            false);

                    int xOrigo = xCenter - 68;
                    int yOrigo = yCenter + 90;
                    drawLine(xOrigo, yOrigo, xOrigo + 150, yOrigo, Color.DARKER_GREY);
                    drawLine(xOrigo, yOrigo, xOrigo, yOrigo - 80, Color.DARKER_GREY);
                    for (int k = -4; k < 5; k++) {
                        int begin = -4;
                        if (k == -4) {
                            begin = -1;
                        }
                        if (k == 0) {
                            begin = -1;
                        }
                        if (k == 4) {
                            begin = -1;
                        }
                        drawLine(
                                xOrigo + begin,
                                yCenter + 50 + k * 10,
                                xOrigo + 5,
                                yCenter + 50 + k * 10,
                                Color.DARKER_GREY);
                    }
                    for (int k = 0; k < 16; k++) {
                        drawLine(xOrigo + k * 10, yOrigo - 4, xOrigo + k * 10, yOrigo + 4, Color.DARKER_GREY);
                    }

                    int time_left = 15 + move_left * 15;
                    int time_right = move_left * 15;

                    String right = "";
                    String left = "";
                    if (time_right == 0) {
                        right = "Now";
                    } else {
                        if (time_right / 60 != 0) {
                            right += (time_right / 60) + "h";
                        }
                        if (time_right % 60 != 0) {
                            right += (time_right % 60) + "min";
                        }
                    }
                    if (time_left / 60 != 0) {
                        left += (time_left / 60) + "h";
                    }
                    if (time_left % 60 != 0) {
                        left += (time_left % 60) + "min";
                    }

                    fontRendererObj.drawString(left, xOrigo - 12, yOrigo + 6, 0x404040);
                    fontRendererObj.drawString(
                            right,
                            xOrigo + 153 - fontRendererObj.getStringWidth(right),
                            yOrigo + 6,
                            0x404040);

                    long[] data = new long[task.amountRecorded.length];
                    int pos = 0;
                    for (int i = task.arrayPos - 1; i >= 0; i--) {
                        data[pos++] = task.amountRecorded[i];
                    }
                    for (int i = task.amountRecorded.length - 1; i >= task.arrayPos; i--) {
                        data[pos++] = task.amountRecorded[i];
                    }

                    long lowest = Long.MAX_VALUE;
                    long highest = Long.MIN_VALUE;
                    int first = (15 * move_left);

                    for (int i = first; i <= first + 15 && i < data.length; i++) {
                        long point = data[i];
                        if (point > highest) {
                            highest = point;
                        }
                        if (point < lowest) {
                            lowest = point;
                        }
                    }

                    double averagey = ((double) highest + lowest) / 2;

                    fontRendererObj.drawString(
                            StringUtils.getFormatedStackSize(highest, false),
                            xOrigo - 1
                                    - fontRendererObj.getStringWidth(StringUtils.getFormatedStackSize(highest, false)),
                            guiTop + 117,
                            0x404040);
                    fontRendererObj.drawString(
                            StringUtils.getFormatedStackSize((long) averagey, false),
                            xOrigo - 1
                                    - fontRendererObj
                                            .getStringWidth(StringUtils.getFormatedStackSize((long) averagey, false)),
                            yCenter + 46,
                            0x404040);
                    fontRendererObj.drawString(
                            StringUtils.getFormatedStackSize(lowest, false),
                            xOrigo - 1
                                    - fontRendererObj.getStringWidth(StringUtils.getFormatedStackSize(lowest, false)),
                            bottom - 23,
                            0x404040);

                    float yScale = 80F / Math.max(highest - lowest, 0.5F);
                    int x = xOrigo + 150;
                    double yOff = data[first] - averagey;
                    int y = (yOrigo - 80 / 2) - (int) (yOff * yScale);

                    for (int i = first + 1; i < data.length; i++) {
                        long point = data[i];
                        int x1 = x - 10;
                        // Stop at the graph's own y axis. This used to test the window's left edge, which only
                        // happened to sit at the axis while the table was 180 wide.
                        if (x1 < xOrigo) {
                            break;
                        }
                        yOff = point - averagey;
                        int y1 = (yOrigo - 80 / 2) - (int) (yOff * yScale);

                        drawLine(x1, y1, x, y, Color.RED);
                        drawRect(x - 1, y - 1, x + 2, y + 2, Color.BLACK);

                        x = x1;
                        y = y1;
                    }
                    drawRect(x - 1, y - 1, x + 2, y + 2, Color.BLACK);
                }
            }
        } else if (current_Tab == 1) {
            itemDisplay_2.renderItemArea(zLevel);
            itemDisplay_2.renderPageNumber(right - 50, guiTop + 66);
        } else if (current_Tab == JOBS_TAB) {
            drawJobsTab(mouse_x, mouse_y);
        }

        super.drawGuiContainerBackgroundLayer(f, mouse_x, mouse_y);
    }

    @Override
    protected void keyTyped(char c, int i) {
        if (current_Tab == 0) {
            if (i == 201) { // PgUp
                itemDisplay_1.prevPage();
            } else if (i == 209) { // PgDn
                itemDisplay_1.nextPage();
            } else {
                super.keyTyped(c, i);
            }
        } else if (current_Tab == 1) {
            if (i == 201) { // PgUp
                itemDisplay_2.prevPage();
            } else if (i == 209) { // PgDn
                itemDisplay_2.nextPage();
            } else {
                super.keyTyped(c, i);
            }
        } else if (current_Tab == JOBS_TAB) {
            if (i == 201) { // PgUp
                turnJobPage(-1);
            } else if (i == 209) { // PgDn
                turnJobPage(1);
            } else {
                super.keyTyped(c, i);
            }
        } else {
            super.keyTyped(c, i);
        }
    }

    @Override
    protected void mouseClicked(int par1, int par2, int par3) {
        if (par3 == 0 && par1 > guiLeft && par1 < guiLeft + 220 && par2 > guiTop && par2 < guiTop + 20) {
            par1 -= guiLeft + 3;
            int tab = Math.max(0, Math.min(par1 / 25, TAB_COUNT - 1));
            if (tab == JOBS_TAB && current_Tab != JOBS_TAB) {
                refreshIn = 0; // fetch straight away on opening the tab
            }
            current_Tab = tab;
        } else {
            if (current_Tab == 0) {
                itemDisplay_1.handleClick(par1, par2, par3);
            } else if (current_Tab == 1) {
                itemDisplay_2.handleClick(par1, par2, par3);
            } else if (current_Tab == JOBS_TAB && par3 == 0 && detailJobId == -1) {
                int row = jobRowAt(par1, par2);
                if (row >= 0) {
                    showJobDetail(sortedRows().get(row).info.getId());
                }
            } else if (current_Tab == JOBS_TAB && par3 == 0 && isOverDetailHeader(par1, par2)) {
                // Clicking the request again collapses it, the same as Back.
                showJobList();
            }
            super.mouseClicked(par1, par2, par3);
        }
    }

    /** The index into {@link #sortedRows()} of the job row under the mouse, or -1. Excludes the cancel buttons. */
    private int jobRowAt(int mouseX, int mouseY) {
        int x = guiLeft + 10;
        int top = guiTop + 40;
        if (mouseX < x || mouseX >= x + rowWidth() || mouseY < top || mouseY >= top + JOB_ROWS * JOB_ROW_HEIGHT) {
            return -1;
        }
        int index = jobPage * JOB_ROWS + (mouseY - top) / JOB_ROW_HEIGHT;
        return index < sortedRows().size() ? index : -1;
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int par1, int par2) {
        super.drawGuiContainerForegroundLayer(par1, par2);
        if (current_Tab == 0) {
            mc.fontRenderer.drawString(
                    StringUtils.translate(PREFIX + "amount"),
                    10,
                    28,
                    Color.getValue(Color.DARKER_GREY),
                    false);
        } else if (current_Tab == 1) {
            mc.fontRenderer.drawString(
                    StringUtils.translate(PREFIX + "crafting"),
                    10,
                    28,
                    Color.getValue(Color.DARKER_GREY),
                    false);
            GuiGraphics.displayItemToolTip(itemDisplay_2.getToolTip(), this, zLevel, guiLeft, guiTop);
        } else if (current_Tab == JOBS_TAB) {
            String title = detailJobId == -1 ? StringUtils.translate(PREFIX + "jobs")
                    : "Request #" + detailJobId + " is waiting on:";
            mc.fontRenderer.drawString(title, 10, 28, Color.getValue(Color.DARKER_GREY), false);
        }
    }

    @Override
    protected void checkButtons() {
        super.checkButtons();
        for (GuiButton button : (List<GuiButton>) buttonList) {
            if (TAB_BUTTON_1.contains(button)) {
                button.visible = current_Tab == 0;
                if (button.displayString.equals("Remove")) {
                    button.enabled = itemDisplay_1.getSelectedItem() != null;
                }
            }
            if (TAB_BUTTON_1_2.contains(button)) {
                button.visible = current_Tab == 0 && itemDisplay_1.getSelectedItem() != null;
            }
            if (TAB_BUTTON_2.contains(button)) {
                button.visible = current_Tab == 1;
            }
        }
        if (detailCancelButton == null) {
            return; // not initialised yet
        }
        boolean jobsTab = current_Tab == JOBS_TAB;
        boolean listView = jobsTab && detailJobId == -1;
        for (GuiButton button : TAB_BUTTON_3_LIST) {
            button.visible = listView;
        }
        for (GuiButton button : TAB_BUTTON_3_DETAIL) {
            button.visible = jobsTab && !listView;
        }
        for (GuiButton button : TAB_BUTTON_3_PAGES) {
            button.visible = jobsTab;
        }
        JobRow detailRow = findRow(detailJobId);
        detailCancelButton.enabled = detailRow != null && detailRow.ended == null;
        List<JobRow> rows = listView ? sortedRows() : null;
        for (int i = 0; i < JOB_ROWS; i++) {
            int index = jobPage * JOB_ROWS + i;
            // Only live jobs can be cancelled; ended rows stay listed without a button.
            jobCancelButtons[i].visible = listView && index < rows.size() && rows.get(index).ended == null;
        }
    }

    public void handlePacket_1(List<ItemIdentifierStack> identList) {
        if (hasSubGui() && getSubGui() instanceof GuiAddTracking) {
            ((GuiAddTracking) getSubGui()).handlePacket(identList);
        } else if (!hasSubGui()) {
            GuiAddTracking sub = new GuiAddTracking(tile);
            setSubGui(sub);
            sub.handlePacket(identList);
        }
    }

    public void handlePacket_2(List<ItemIdentifierStack> identList) {
        itemDisplay_2.setItemList(identList);
    }
}
