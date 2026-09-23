package logisticspipes.utils.gui;

import java.util.function.IntConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

import org.lwjgl.input.Keyboard;

/**
 * A small typed number box, for guis that need more entry fields than a pair of arrow buttons can carry.
 * <p>
 * Coordinates are relative to the gui, so callers pass {@code guiLeft}/{@code guiTop} when drawing and when handling
 * clicks. The value is committed when the box loses focus or Enter is pressed, never on every keystroke, so a packet
 * isn't sent per digit.
 */
public class GuiNumberField {

    private static final int BACKGROUND = 0xFF000000;
    private static final int BORDER = 0xFFA0A0A0;
    private static final int BORDER_FOCUSED = 0xFFFFFFFF;
    private static final int BORDER_DISABLED = 0xFF5A5A5A;
    private static final int TEXT = 0xE0E0E0;
    private static final int TEXT_DIMMED = 0x707070;

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int min;
    private final int max;
    private final IntConsumer onCommit;

    private String text = "";
    private boolean focused;
    private boolean enabled = true;
    /** Shown instead of the value at {@link #placeholderValue}, e.g. "Off" for an unset satellite id. */
    private String emptyText = null;
    /**
     * The value that means "not set". It is drawn dimmed, so a default never reads as something the player chose. The
     * lowest value by default, which suits a satellite id; a chance box sets it to 100.
     */
    private int placeholderValue;
    private String tooltip = null;

    public GuiNumberField(int x, int y, int width, int height, int min, int max, IntConsumer onCommit) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.min = min;
        this.max = max;
        this.placeholderValue = min;
        this.onCommit = onCommit;
    }

    public GuiNumberField setEmptyText(String emptyText) {
        this.emptyText = emptyText;
        return this;
    }

    public GuiNumberField setPlaceholderValue(int placeholderValue) {
        this.placeholderValue = placeholderValue;
        return this;
    }

    public GuiNumberField setTooltip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    public String getTooltip() {
        return tooltip;
    }

    public boolean isMouseOver(int mouseX, int mouseY, int guiLeft, int guiTop) {
        return mouseX >= guiLeft + x && mouseX < guiLeft + x + width
                && mouseY >= guiTop + y
                && mouseY < guiTop + y + height;
    }

    public GuiNumberField setEnabled(boolean enabled) {
        if (!enabled) {
            focused = false;
        }
        this.enabled = enabled;
        return this;
    }

    /** Takes the value from the module. Ignored while the player is typing in this box. */
    public void setValue(int value) {
        if (!focused) {
            text = Integer.toString(clamp(value));
        }
    }

    public int getValue() {
        return clamp(parse());
    }

    public boolean isFocused() {
        return focused;
    }

    public void unfocus() {
        if (focused) {
            focused = false;
            commit();
        }
    }

    /** @return true if this box took the click */
    public boolean mouseClicked(int mouseX, int mouseY, int guiLeft, int guiTop) {
        boolean hit = enabled && isMouseOver(mouseX, mouseY, guiLeft, guiTop);
        if (hit) {
            focused = true;
        } else {
            unfocus();
        }
        return hit;
    }

    /** @return true if the key was used up here, so the gui doesn't also act on it */
    public boolean keyTyped(char typedChar, int keyCode) {
        if (!focused) {
            return false;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER
                || keyCode == Keyboard.KEY_TAB
                || keyCode == Keyboard.KEY_ESCAPE) {
            unfocus();
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (!text.isEmpty()) {
                text = text.substring(0, text.length() - 1);
            }
            return true;
        }
        if (typedChar >= '0' && typedChar <= '9' && Integer.toString(max).length() > text.length()) {
            text += typedChar;
            return true;
        }
        // Swallow everything else, so typing 'e' in a box doesn't close the gui.
        return true;
    }

    public void draw(Minecraft mc, int guiLeft, int guiTop) {
        int left = guiLeft + x;
        int top = guiTop + y;
        // A box with nothing to configure recedes by its border; a box holding a default recedes by its text. Keeping
        // those apart matters, because both read as "inactive" at a glance.
        int border = BORDER_DISABLED;
        if (focused) {
            border = BORDER_FOCUSED;
        } else if (enabled) {
            border = BORDER;
        }
        Gui.drawRect(left, top, left + width, top + height, border);
        Gui.drawRect(left + 1, top + 1, left + width - 1, top + height - 1, BACKGROUND);
        boolean placeholder = !focused && parse() == placeholderValue;
        String shown = placeholder && emptyText != null ? emptyText : text;
        if (focused) {
            shown = shown + "_";
        }
        mc.fontRenderer
                .drawString(shown, left + 3, top + (height - 8) / 2 + 1, enabled && !placeholder ? TEXT : TEXT_DIMMED);
    }

    private void commit() {
        int value = clamp(parse());
        text = Integer.toString(value);
        if (onCommit != null) {
            onCommit.accept(value);
        }
    }

    private int parse() {
        if (text.isEmpty()) {
            return min;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return min;
        }
    }

    private int clamp(int value) {
        return Math.max(min, Math.min(max, value));
    }
}
