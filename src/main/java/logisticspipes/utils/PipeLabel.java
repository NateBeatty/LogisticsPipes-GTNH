package logisticspipes.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;

/**
 * A player-given name and tags for a chassis pipe with a Label Upgrade, so it can be found again in the Statistics
 * Table's crafter list. Stored in the pipe's NBT as {@code lpLabel}, and only once something was typed.
 */
public final class PipeLabel {

    public static final int MAX_NAME_LENGTH = 40;
    /** Length of the tags as typed, commas included. */
    public static final int MAX_TAGS_LENGTH = 100;
    public static final int MAX_TAGS = 12;

    public static final PipeLabel EMPTY = new PipeLabel("", Collections.emptyList(), null);

    private final String name;
    private final List<String> tags;
    /**
     * The machine to show for this chassis instead of the block it faces, or null. Set by the Auto button when the
     * chassis faces a multiblock's bus or hatch, so the list shows the multiblock. Item and damage only.
     */
    private final ItemStack icon;

    private PipeLabel(String name, List<String> tags, ItemStack icon) {
        this.name = name;
        this.tags = Collections.unmodifiableList(tags);
        this.icon = icon;
    }

    /** This label with another icon, reduced to item and damage; null removes it. */
    public PipeLabel withIcon(ItemStack stack) {
        return new PipeLabel(name, tags, plainIcon(stack));
    }

    private static ItemStack plainIcon(ItemStack stack) {
        return stack == null || stack.getItem() == null ? null
                : new ItemStack(stack.getItem(), 1, stack.getItemDamage());
    }

    public ItemStack getIcon() {
        return icon;
    }

    /** Whether two icons show the same thing; both may be null. */
    public static boolean sameIcon(ItemStack a, ItemStack b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage();
    }

    /** Builds a label from what was typed: trims the name, splits the tags on commas and drops blanks and repeats. */
    public static PipeLabel of(String name, String tags) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.length() > MAX_NAME_LENGTH) {
            cleanName = cleanName.substring(0, MAX_NAME_LENGTH);
        }
        List<String> cleanTags = new ArrayList<>();
        if (tags != null) {
            String typed = tags.length() > MAX_TAGS_LENGTH ? tags.substring(0, MAX_TAGS_LENGTH) : tags;
            for (String tag : typed.split(",")) {
                String t = tag.trim();
                if (!t.isEmpty() && !containsIgnoreCase(cleanTags, t) && cleanTags.size() < MAX_TAGS) {
                    cleanTags.add(t);
                }
            }
        }
        return new PipeLabel(cleanName, cleanTags, null);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    public String getName() {
        return name;
    }

    public List<String> getTags() {
        return tags;
    }

    /** The tags as they are typed into the chassis GUI. */
    public String getTagsText() {
        return String.join(", ", tags);
    }

    public boolean isEmpty() {
        return name.isEmpty() && tags.isEmpty() && icon == null;
    }

    /** Whether one of the tags is this one, ignoring case and spaces, for the crafter list's {@code #tag} filter. */
    public boolean hasTag(String tag) {
        String wanted = squash(tag);
        for (String t : tags) {
            if (squash(t).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static String squash(String s) {
        return s.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    public void writeToNBT(NBTTagCompound tag) {
        tag.setString("name", name);
        NBTTagList list = new NBTTagList();
        for (String t : tags) {
            list.appendTag(new NBTTagString(t));
        }
        tag.setTag("tags", list);
        if (icon != null) {
            NBTTagCompound iconTag = new NBTTagCompound();
            icon.writeToNBT(iconTag);
            tag.setTag("icon", iconTag);
        }
    }

    public static PipeLabel readFromNBT(NBTTagCompound tag) {
        NBTTagList list = tag.getTagList("tags", 8); // 8: string tags
        List<String> tags = new ArrayList<>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            tags.add(list.getStringTagAt(i));
        }
        PipeLabel label = of(tag.getString("name"), String.join(",", tags));
        // An icon whose mod was removed loads as null and is dropped.
        return tag.hasKey("icon") ? label.withIcon(ItemStack.loadItemStackFromNBT(tag.getCompoundTag("icon"))) : label;
    }

    public void write(LPDataOutputStream out) throws IOException {
        out.writeUTF(name);
        out.writeUTF(String.join(",", tags));
        out.writeItemStack(icon);
    }

    public static PipeLabel read(LPDataInputStream in) throws IOException {
        PipeLabel label = of(in.readUTF(), in.readUTF());
        return label.withIcon(in.readItemStack());
    }
}
