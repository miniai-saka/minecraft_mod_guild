package dev.guildquests.gui;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * ItemStack builders shared by all screen handlers.
 * Item-stack display data (custom name, lore, skull texture) is version-sensitive
 * (NBT on 1.20.1, data components on 1.20.5+); keep all of it here so a version
 * port only rewrites this one class.
 */
public final class GuildItems {
    /** mcheads.ru "Quartz 0".."Quartz 9" texture payloads (index = digit). */
    private static final String[] DIGIT_TEXTURES = {
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWY4ODZkOWM0MGVmN2Y1MGMyMzg4MjQ3OTJjNDFmYmZiNTRmNjY1ZjE1OWJmMWJjYjBiMjdiM2VhZDM3M2IifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTBhMTllMjNkMjFmMmRiMDYzY2M1NWU5OWFlODc0ZGM4YjIzYmU3NzliZTM0ZTUyZTdjN2I5YTI1In19fQ==",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2M1OTZhNDFkYWVhNTFiZTJlOWZlYzdkZTJkODkwNjhlMmZhNjFjOWQ1N2E4YmRkZTQ0YjU1OTM3YjYwMzcifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjg1ZDRmZGE1NmJmZWI4NTEyNDQ2MGZmNzJiMjUxZGNhOGQxZGViNjU3ODA3MGQ2MTJiMmQzYWRiZjVhOCJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzg1MmEyNWZlNjljYTg2ZmI5ODJmYjNjYzdhYzk3OTNmNzM1NmI1MGI5MmNiMGUxOTNkNmI0NjMyYTliZDYyOSJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzRlZTdkOTU0ZWIxNGE1Y2NkMzQ2MjY2MjMxYmY5YTY3MTY1MjdiNTliYmNkNzk1NmNlZjA0YTlkOWIifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjY4MmEzYWU5NDgzNzRlMDM3ZTNkN2RkNjg3ZDU5ZDE4NWRkMmNjOGZjMDlkZmViNDJmOThmOGQyNTllNWMzIn19fQ==",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGVhMzBjMjRjNjBiM2JjMWFmNjU4ZWY2NjFiNzcxYzQ4ZDViOWM5ZTI4MTg4Y2Y5ZGU5ZjgzMjQyMmU1MTAifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjZhYmFmZDAyM2YyMzBlNDQ4NWFhZjI2ZTE5MzY4ZjU5ODBkNGYxNGE1OWZjYzZkMTFhNDQ2Njk5NDg5MiJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ3OTEwZTEwMzM0Zjg5MGE2MjU0ODNhYzBjODI0YjVlNGExYTRiMTVhOTU2MzI3YTNlM2FlNDU4ZDllYTQifX19"
    };

    private GuildItems() {
    }

    public static ItemStack item(Item item, String name, String... lore) {
        return item(item, Formatting.WHITE, name, lore);
    }

    public static ItemStack item(Item item, Formatting nameFormat, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.setCustomName(Text.literal(name).formatted(nameFormat));
        Text[] lines = new Text[lore.length];
        for (int i = 0; i < lore.length; i++) lines[i] = Text.literal(lore[i]).formatted(Formatting.GRAY);
        setLore(stack, lines);
        return stack;
    }

    /** Player head showing the owner's skin (resolved client-side from the profile id). */
    public static ItemStack headItem(String ownerName, UUID ownerId, String name, String... lore) {
        return headItemWithTexture(ownerName, ownerId, null, name, lore);
    }

    /**
     * Player head with an optional custom texture. When {@code textureValue} is non-null it is written as
     * SkullOwner.Properties.Textures so the client renders that exact texture instead of resolving the id.
     */
    public static ItemStack headItemWithTexture(String ownerName, UUID ownerId, String textureValue, String name, String... lore) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        NbtCompound skull = stack.getOrCreateSubNbt("SkullOwner");
        skull.putString("Id", ownerId.toString());
        skull.putString("Name", ownerName);
        if (textureValue != null) {
            NbtCompound property = new NbtCompound();
            property.putString("Value", textureValue);
            NbtList textures = new NbtList();
            textures.add(property);
            NbtCompound properties = new NbtCompound();
            properties.put("textures", textures);
            skull.put("Properties", properties);
        }
        stack.setCustomName(Text.literal(name).formatted(Formatting.WHITE));
        Text[] lines = new Text[lore.length];
        for (int i = 0; i < lore.length; i++) lines[i] = Text.literal(lore[i]).formatted(Formatting.GRAY);
        setLore(stack, lines);
        return stack;
    }

    /**
     * Copy of {@code base} with an overridden name and extra gray lore lines appended
     * after the item's existing lore (so enchantments/custom lore stay visible).
     * Used to render escrowed/mailbox items without mutating the stored stack.
     */
    public static ItemStack decorateCopy(ItemStack base, Formatting nameFormat, String name, String... extraLore) {
        ItemStack stack = base.copy();
        stack.setCustomName(Text.literal(name).formatted(nameFormat));
        NbtCompound display = stack.getOrCreateSubNbt("display");
        NbtList lore = new NbtList();
        if (display.contains("Lore", NbtElement.LIST_TYPE)) {
            NbtList existing = display.getList("Lore", NbtElement.STRING_TYPE);
            for (int i = 0; i < existing.size(); i++) lore.add(existing.get(i));
        }
        for (String line : extraLore) {
            lore.add(NbtString.of(Text.Serializer.toJson(Text.literal(line).formatted(Formatting.GRAY))));
        }
        display.put("Lore", lore);
        return stack;
    }

    public static ItemStack digitHead(int digit, String placeLabel) {
        return headItemWithTexture("digit", new UUID(0, digit), DIGIT_TEXTURES[digit],
                "送金額 " + placeLabel + ": " + digit,
                "左クリックで +1（0-9 で循環）",
                "右クリックで -1");
    }

    private static void setLore(ItemStack stack, Text... lines) {
        if (lines.length == 0) return;
        NbtCompound display = stack.getOrCreateSubNbt("display");
        NbtList lore = new NbtList();
        for (Text line : lines) {
            lore.add(NbtString.of(Text.Serializer.toJson(line)));
        }
        display.put("Lore", lore);
    }
}
