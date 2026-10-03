package logisticspipes.gui.popup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Keyboard;

import logisticspipes.utils.Color;
import logisticspipes.utils.gui.GuiGraphics;
import logisticspipes.utils.gui.SmallGuiButton;
import logisticspipes.utils.gui.SubGuiScreen;
import logisticspipes.utils.string.StringUtils;

/**
 * Every tag on the network, for the Statistics Table's crafter list. Clicking a tag adds it to the list's search as
 * {@code #tag}, or takes it out again; the search box at the top only narrows this list.
 */
public class GuiTagPicker extends SubGuiScreen {

    private static final int ROWS = 10;
    private static final int ROW_HEIGHT = 12;
    private static final int LIST_TOP = 36;

    /** A tag as typed on one of the chassis, and how many chassis have it. */
    public static final class TagCount {

        public final String tag;
        public final int count;

        public TagCount(String tag, int count) {
            this.tag = tag;
            this.count = count;
        }
    }

    private final List<TagCount> tags;
    private final Predicate<String> isSelected;
    private final Consumer<String> toggle;
    private List<TagCount> visible = new ArrayList<>();
    private GuiTextField search;
    private int page = 0;

    /**
     * @param tags       the tags to offer, in display order
     * @param isSelected whether a tag is in the crafter list's search
     * @param toggle     adds the tag to the search, or removes it
     */
    public GuiTagPicker(List<TagCount> tags, Predicate<String> isSelected, Consumer<String> toggle) {
        super(180, LIST_TOP + ROWS * ROW_HEIGHT + 32, 0, 0);
        this.tags = tags;
        this.isSelected = isSelected;
        this.toggle = toggle;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        buttonList.add(new SmallGuiButton(0, guiLeft + 10, bottom - 20, 10, 10, "<"));
        buttonList.add(new SmallGuiButton(1, guiLeft + 50, bottom - 20, 10, 10, ">"));
        buttonList.add(new GuiButton(2, right - 50, bottom - 24, 40, 18, "Done"));
        String typed = search != null ? search.getText() : "";
        search = new GuiTextField(fontRendererObj, guiLeft + 11, guiTop + 20, xSize - 22, 12);
        search.setMaxStringLength(32);
        search.setText(typed);
        search.setFocused(true);
        filter();
    }

    private void filter() {
        String wanted = search.getText().trim().toLowerCase(Locale.ROOT);
        List<TagCount> list = new ArrayList<>();
        for (TagCount t : tags) {
            if (wanted.isEmpty() || t.tag.toLowerCase(Locale.ROOT).contains(wanted)) {
                list.add(t);
            }
        }
        visible = list;
        page = Math.min(page, pages() - 1);
    }

    private int pages() {
        return Math.max(1, (visible.size() + ROWS - 1) / ROWS);
    }

    /** The tag under the mouse, or null. */
    private TagCount tagAt(int mouseX, int mouseY) {
        int top = guiTop + LIST_TOP;
        if (mouseX < guiLeft + 10 || mouseX >= right - 10 || mouseY < top || mouseY >= top + ROWS * ROW_HEIGHT) {
            return null;
        }
        int index = page * ROWS + (mouseY - top) / ROW_HEIGHT;
        return index < visible.size() ? visible.get(index) : null;
    }

    @Override
    protected void renderGuiBackground(int mouseX, int mouseY) {
        GuiGraphics.drawGuiBackGround(mc, guiLeft, guiTop, right, bottom, zLevel, true);
        fontRendererObj.drawString("Tags on this network", guiLeft + 10, guiTop + 8, 0x404040);
        search.drawTextBox();
        int top = guiTop + LIST_TOP;
        if (visible.isEmpty()) {
            String none = tags.isEmpty() ? "No chassis has tags yet." : "No tag matches.";
            fontRendererObj.drawString(none, guiLeft + 12, top + 4, 0x404040);
        }
        TagCount hovered = tagAt(mouseX, mouseY);
        for (int i = 0; i < ROWS; i++) {
            int index = page * ROWS + i;
            if (index >= visible.size()) {
                break;
            }
            TagCount t = visible.get(index);
            int y = top + i * ROW_HEIGHT;
            if (t == hovered) {
                drawRect(guiLeft + 10, y, right - 10, y + ROW_HEIGHT, Color.getValue(Color.LIGHTER_GREY));
            }
            boolean selected = isSelected.test(t.tag);
            // A small box, filled when the tag is part of the search.
            drawRect(guiLeft + 12, y + 2, guiLeft + 20, y + 10, 0xFF404040);
            drawRect(guiLeft + 13, y + 3, guiLeft + 19, y + 9, selected ? 0xFF3050A0 : 0xFFC6C6C6);
            String count = Integer.toString(t.count);
            int countWidth = fontRendererObj.getStringWidth(count);
            fontRendererObj.drawString(count, right - 12 - countWidth, y + 2, 0x808080);
            fontRendererObj.drawString(
                    StringUtils.getWithMaxWidth(t.tag, xSize - 44 - countWidth, fontRendererObj),
                    guiLeft + 24,
                    y + 2,
                    selected ? 0x3050A0 : 0x404040);
        }
        String pageText = (page + 1) + "/" + pages();
        fontRendererObj.drawString(
                pageText,
                guiLeft + 35 - fontRendererObj.getStringWidth(pageText) / 2,
                bottom - 19,
                0x404040);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0 || button.id == 1) {
            page = Math.floorMod(page + (button.id == 1 ? 1 : -1), pages());
        } else if (button.id == 2) {
            exitGui();
        }
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        search.mouseClicked(x, y, button);
        TagCount t = button == 0 ? tagAt(x, y) : null;
        if (t != null) {
            toggle.accept(t.tag);
        }
        super.mouseClicked(x, y, button);
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            exitGui();
        } else if (key == 201) { // PgUp
            page = Math.floorMod(page - 1, pages());
        } else if (key == 209) { // PgDn
            page = Math.floorMod(page + 1, pages());
        } else if (search.textboxKeyTyped(c, key)) {
            page = 0;
            filter();
        }
    }
}
