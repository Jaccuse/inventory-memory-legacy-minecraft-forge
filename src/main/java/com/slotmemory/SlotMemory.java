package com.slotmemory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

@Mod(
        modid = SlotMemory.MODID,
        name = SlotMemory.NAME,
        version = SlotMemory.VERSION,
        acceptedMinecraftVersions = "[1.8.9]",
        acceptableRemoteVersions = "*",
        clientSideOnly = true
)
public class SlotMemory {
    public static final String MODID = "slotmemory";
    public static final String NAME = "Slot Memory";
    /** Replaced with the Gradle project version at build time. */
    public static final String VERSION = "@VERSION@";

    /** InventoryPlayer.mainInventory: 0-8 hotbar, 9-35 main inventory. */
    private static final int HOTBAR_SIZE = 9;
    private static final int MAIN_INVENTORY_SIZE = 36;

    /** NBT type id for compound tags. */
    private static final int NBT_COMPOUND = 10;

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static KeyBinding commitKey;
    private static KeyBinding sortNowKey;
    private static KeyBinding toggleAutoSortingKey;
    private static KeyBinding toggleAssignKey;

    /**
     * Maps item identity -> one or more InventoryPlayer mainInventory indices.
     *
     * TreeSet keeps assigned targets in natural slot order, which prioritizes
     * hotbar slots before main inventory slots.
     */
    private final Map<ItemKey, TreeSet<Integer>> memory = new HashMap<ItemKey, TreeSet<Integer>>();

    /**
     * Optional automatic mode.
     *
     * When false, the mod only sorts when the sort-now key is pressed.
     * When true, the mod periodically nudges remembered items back into place.
     */
    private boolean autoSortingEnabled = false;

    private boolean commitWasDown = false;
    private boolean sortNowWasDown = false;
    private boolean toggleAutoSortingWasDown = false;
    private boolean toggleAssignWasDown = false;

    /**
     * Conservative delay between automated inventory click sequences.
     * This is only used for automatic sorting, not the manual sort-now key.
     */
    private int clickCooldownTicks = 0;

    /**
     * Current save profile for remembered slot rules.
     *
     * Multiplayer profile key: server address.
     * Singleplayer profile key: world name + seed.
     */
    private String activeMemoryProfileId = null;

    /**
     * SHA-1 hashed filename for the current profile.
     */
    private String activeMemoryProfileFileName = null;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        commitKey = new KeyBinding(
                "Memorize current inventory layout",
                Keyboard.KEY_NUMPAD7,
                "Slot Memory"
        );

        sortNowKey = new KeyBinding(
                "Sort inventory using Slot Memory",
                Keyboard.KEY_NUMPAD8,
                "Slot Memory"
        );

        toggleAutoSortingKey = new KeyBinding(
                "Toggle automatic Slot Memory sorting",
                Keyboard.KEY_NUMPAD6,
                "Slot Memory"
        );

        toggleAssignKey = new KeyBinding(
                "Toggle hovered item assignment",
                Keyboard.KEY_NUMPAD9,
                "Slot Memory"
        );

        ClientRegistry.registerKeyBinding(commitKey);
        ClientRegistry.registerKeyBinding(sortNowKey);
        ClientRegistry.registerKeyBinding(toggleAutoSortingKey);
        ClientRegistry.registerKeyBinding(toggleAssignKey);

        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        saveMemoryForActiveProfile();

        activeMemoryProfileId = null;
        activeMemoryProfileFileName = null;
        memory.clear();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        ensureMemoryProfileLoaded();

        handleHotkeys();

        if (!autoSortingEnabled) {
            return;
        }

        if (memory.isEmpty()) {
            return;
        }

        if (clickCooldownTicks > 0) {
            clickCooldownTicks--;
            return;
        }

        if (!canTouchInventory()) {
            return;
        }

        Container container = getActiveContainer();

        if (tryOneInventoryCorrection(container)) {
            clickCooldownTicks = 3;
        }
    }

    private void handleHotkeys() {
        /*
         * Avoid hotkeys while chat/menus are open. Allow them in normal play and
         * while a container/inventory screen is open.
         */
        if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiContainer)) {
            updateKeyEdgesOnly();
            return;
        }

        boolean commitDown = isKeyBindingPhysicallyDown(commitKey);
        boolean sortNowDown = isKeyBindingPhysicallyDown(sortNowKey);
        boolean toggleAutoSortingDown = isKeyBindingPhysicallyDown(toggleAutoSortingKey);
        boolean toggleAssignDown = isKeyBindingPhysicallyDown(toggleAssignKey);

        if (commitDown && !commitWasDown) {
            commitCurrentInventoryLayout();
        }

        if (sortNowDown && !sortNowWasDown) {
            sortInventoryNow();
        }

        if (toggleAutoSortingDown && !toggleAutoSortingWasDown) {
            autoSortingEnabled = !autoSortingEnabled;

            chat(
                    EnumChatFormatting.YELLOW + "Automatic sorting: " +
                            (autoSortingEnabled
                                    ? EnumChatFormatting.GREEN + "ON"
                                    : EnumChatFormatting.RED + "OFF")
            );
        }

        if (toggleAssignDown && !toggleAssignWasDown) {
            handleHoveredAssignmentToggle();
        }

        commitWasDown = commitDown;
        sortNowWasDown = sortNowDown;
        toggleAutoSortingWasDown = toggleAutoSortingDown;
        toggleAssignWasDown = toggleAssignDown;
    }

    private void updateKeyEdgesOnly() {
        commitWasDown = isKeyBindingPhysicallyDown(commitKey);
        sortNowWasDown = isKeyBindingPhysicallyDown(sortNowKey);
        toggleAutoSortingWasDown = isKeyBindingPhysicallyDown(toggleAutoSortingKey);
        toggleAssignWasDown = isKeyBindingPhysicallyDown(toggleAssignKey);
    }

    private boolean isKeyBindingPhysicallyDown(KeyBinding keyBinding) {
        int code = keyBinding.getKeyCode();

        /*
         * Minecraft encodes mouse buttons as negative key codes:
         * mouse button = keyCode + 100.
         */
        if (code < 0) {
            int mouseButton = code + 100;
            return mouseButton >= 0 && Mouse.isButtonDown(mouseButton);
        }

        /*
         * Keyboard.KEY_NONE is 0. Treat it as unbound.
         */
        return code > 0 && Keyboard.isKeyDown(code);
    }

    private void commitCurrentInventoryLayout() {
        memory.clear();

        int assignments = 0;
        int itemTypes = 0;

        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];

            if (stack == null) {
                continue;
            }

            ItemKey key = ItemKey.from(stack);

            if (!memory.containsKey(key)) {
                itemTypes++;
            }

            if (assignSlot(key, i)) {
                assignments++;
            }
        }

        chat(
                EnumChatFormatting.GREEN + "Memorized " +
                        EnumChatFormatting.AQUA + assignments +
                        EnumChatFormatting.GREEN + plural(assignments, " slot assignment") +
                        EnumChatFormatting.GRAY + " across " +
                        EnumChatFormatting.AQUA + itemTypes +
                        EnumChatFormatting.GRAY + plural(itemTypes, " item type")
        );

        saveMemoryForActiveProfile();
    }

    private void handleHoveredAssignmentToggle() {
        if (!(mc.currentScreen instanceof GuiContainer)) {
            chat(EnumChatFormatting.RED + "Open your inventory and hover an item first.");
            return;
        }

        GuiContainer gui = (GuiContainer) mc.currentScreen;
        Slot hovered = getHoveredSlot(gui);

        if (hovered == null || !hovered.getHasStack()) {
            chat(EnumChatFormatting.RED + "Hover an item in your inventory/hotbar first.");
            return;
        }

        if (hovered.inventory != mc.thePlayer.inventory) {
            chat(EnumChatFormatting.RED + "Only player inventory/hotbar slots can be assigned.");
            return;
        }

        int invIndex = hovered.getSlotIndex();

        if (!isMainInventoryIndex(invIndex)) {
            chat(EnumChatFormatting.RED + "Armor/crafting slots are not supported.");
            return;
        }

        ItemStack stack = hovered.getStack();
        ItemKey key = ItemKey.from(stack);

        if (isSlotAssignedToKey(key, invIndex)) {
            removeAssignment(key, invIndex);

            chat(
                    EnumChatFormatting.GRAY + "Unassigned " +
                            EnumChatFormatting.WHITE + stack.getDisplayName() +
                            EnumChatFormatting.GRAY + " from " +
                            EnumChatFormatting.AQUA + describeInventoryIndex(invIndex)
            );

            saveMemoryForActiveProfile();

            return;
        }

        /*
         * One physical slot cannot be owned by two different item identities.
         * Multiple physical slots can belong to the same item identity.
         */
        removeAssignmentsToSlot(invIndex);
        assignSlot(key, invIndex);

        chat(
                EnumChatFormatting.GREEN + "Assigned " +
                        EnumChatFormatting.WHITE + stack.getDisplayName() +
                        EnumChatFormatting.GREEN + " -> " +
                        EnumChatFormatting.AQUA + describeInventoryIndex(invIndex)
        );

        saveMemoryForActiveProfile();
    }

    private void sortInventoryNow() {
        if (memory.isEmpty()) {
            chat(EnumChatFormatting.YELLOW + "No memorized slot rules.");
            return;
        }

        if (!canTouchInventory()) {
            chat(EnumChatFormatting.RED + "Cannot sort while holding an item or using this screen.");
            return;
        }

        Container container = getActiveContainer();

        /* Upper bound on corrections per keypress, as a guard against oscillation. */
        int maxActions = MAIN_INVENTORY_SIZE * 2;
        int actions = 0;

        while (actions < maxActions) {
            if (!tryOneInventoryCorrection(container)) {
                break;
            }

            actions++;
        }

        if (actions == 0) {
            chat(EnumChatFormatting.GRAY + "Inventory already matches remembered slots.");
        } else {
            chat(
                    EnumChatFormatting.GREEN + "Slot Memory sort complete. " +
                            EnumChatFormatting.AQUA + actions +
                            EnumChatFormatting.GREEN + plural(actions, " action") + "."
            );
        }
    }

    private boolean canTouchInventory() {
        /*
         * Do not touch inventory while the player is holding an item stack
         * on the cursor.
         */
        if (mc.thePlayer.inventory.getItemStack() != null) {
            return false;
        }

        /*
         * Only operate when no screen is open, or when a container GUI is open.
         * This avoids triggering while chat, pause menu, controls menu, etc. are open.
         */
        return mc.currentScreen == null || mc.currentScreen instanceof GuiContainer;
    }

    private Container getActiveContainer() {
        Container container = mc.thePlayer.openContainer;

        if (container == null) {
            container = mc.thePlayer.inventoryContainer;
        }

        return container;
    }

    private void ensureMemoryProfileLoaded() {
        String currentProfileId = getCurrentMemoryProfileId();

        if (currentProfileId == null || currentProfileId.length() == 0) {
            return;
        }

        if (currentProfileId.equals(activeMemoryProfileId)) {
            return;
        }

        saveMemoryForActiveProfile();

        activeMemoryProfileId = currentProfileId;
        activeMemoryProfileFileName = hashProfileId(currentProfileId) + ".dat";

        memory.clear();

        int loadedAssignments = loadMemoryForActiveProfile();

        chat(
                EnumChatFormatting.GRAY + "Loaded " +
                        EnumChatFormatting.AQUA + loadedAssignments +
                        EnumChatFormatting.GRAY + plural(loadedAssignments, " remembered slot assignment") + "."
        );
    }

    private String getCurrentMemoryProfileId() {
        ServerData serverData = mc.getCurrentServerData();

        if (serverData != null && serverData.serverIP != null && serverData.serverIP.trim().length() > 0) {
            return "mp|" + serverData.serverIP.trim().toLowerCase(Locale.ROOT);
        }

        if (mc.theWorld != null) {
            WorldInfo worldInfo = mc.theWorld.getWorldInfo();

            if (worldInfo != null && worldInfo.getWorldName() != null && worldInfo.getWorldName().trim().length() > 0) {
                return "sp|" + worldInfo.getWorldName().trim() + "|" + worldInfo.getSeed();
            }
        }

        return null;
    }

    private File getSlotMemoryDirectory() {
        return new File(mc.mcDataDir, "config/slotmemory");
    }

    private File getActiveMemoryFile() {
        if (activeMemoryProfileFileName == null || activeMemoryProfileFileName.length() == 0) {
            return null;
        }

        return new File(getSlotMemoryDirectory(), activeMemoryProfileFileName);
    }

    private void saveMemoryForActiveProfile() {
        if (activeMemoryProfileId == null || activeMemoryProfileFileName == null) {
            return;
        }

        File directory = getSlotMemoryDirectory();

        if (!directory.exists() && !directory.mkdirs()) {
            chat(EnumChatFormatting.RED + "Could not create Slot Memory config directory.");
            return;
        }

        File file = getActiveMemoryFile();

        if (file == null) {
            return;
        }

        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("version", 1);
        root.setString("profile", activeMemoryProfileId);

        NBTTagList entries = new NBTTagList();

        for (Map.Entry<ItemKey, TreeSet<Integer>> memoryEntry : memory.entrySet()) {
            ItemKey key = memoryEntry.getKey();
            TreeSet<Integer> slots = memoryEntry.getValue();

            if (key == null || slots == null || slots.isEmpty()) {
                continue;
            }

            NBTTagCompound entryTag = new NBTTagCompound();

            entryTag.setInteger("itemId", key.itemId);
            entryTag.setInteger("damage", key.damage);
            entryTag.setIntArray("slots", toIntArray(slots));

            if (key.tag != null) {
                entryTag.setTag("tag", key.tag.copy());
            }

            entries.appendTag(entryTag);
        }

        root.setTag("entries", entries);

        try (OutputStream outputStream = new FileOutputStream(file)) {
            CompressedStreamTools.writeCompressed(root, outputStream);
        } catch (IOException e) {
            chat(EnumChatFormatting.RED + "Failed to save remembered slot rules.");
        }
    }

    private int loadMemoryForActiveProfile() {
        File file = getActiveMemoryFile();

        if (file == null || !file.exists() || !file.isFile()) {
            return 0;
        }

        try (InputStream inputStream = new FileInputStream(file)) {
            NBTTagCompound root = CompressedStreamTools.readCompressed(inputStream);

            if (root == null) {
                return 0;
            }

            NBTTagList entries = root.getTagList("entries", NBT_COMPOUND);
            int loadedAssignments = 0;

            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entryTag = entries.getCompoundTagAt(i);

                int itemId = entryTag.getInteger("itemId");
                int damage = entryTag.getInteger("damage");

                Item item = Item.getItemById(itemId);

                if (item == null) {
                    continue;
                }

                NBTTagCompound copiedTag = null;

                if (entryTag.hasKey("tag", NBT_COMPOUND)) {
                    copiedTag = (NBTTagCompound) entryTag.getCompoundTag("tag").copy();

                    if (copiedTag.getKeySet().isEmpty()) {
                        copiedTag = null;
                    }
                }

                ItemKey key = new ItemKey(itemId, damage, copiedTag);
                int[] slots = entryTag.getIntArray("slots");

                if (slots == null || slots.length == 0) {
                    continue;
                }

                for (int slot : slots) {
                    if (!isMainInventoryIndex(slot)) {
                        continue;
                    }

                    /*
                     * A physical slot can only belong to one item identity.
                     * This protects against stale or manually edited save data.
                     */
                    removeAssignmentsToSlot(slot);

                    if (assignSlot(key, slot)) {
                        loadedAssignments++;
                    }
                }
            }

            return loadedAssignments;
        } catch (IOException e) {
            chat(EnumChatFormatting.RED + "Failed to load remembered slot rules.");
            return 0;
        }
    }

    private static int[] toIntArray(TreeSet<Integer> slots) {
        int[] result = new int[slots.size()];
        int index = 0;

        for (int slot : slots) {
            result[index++] = slot;
        }

        return result;
    }

    private String hashProfileId(String profileId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashed = digest.digest(profileId.getBytes(StandardCharsets.UTF_8));

            StringBuilder builder = new StringBuilder();

            for (byte b : hashed) {
                String hex = Integer.toHexString(b & 255);

                if (hex.length() == 1) {
                    builder.append('0');
                }

                builder.append(hex);
            }

            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return "default";
        }
    }

    private boolean tryOneInventoryCorrection(Container container) {
        /*
         * Copy the entry list so correction is not sensitive to future memory
         * edits during iteration.
         */
        ArrayList<Map.Entry<ItemKey, TreeSet<Integer>>> entries =
                new ArrayList<Map.Entry<ItemKey, TreeSet<Integer>>>(memory.entrySet());

        for (Map.Entry<ItemKey, TreeSet<Integer>> entry : entries) {
            ItemKey key = entry.getKey();
            TreeSet<Integer> assignedSlots = entry.getValue();

            if (assignedSlots == null || assignedSlots.isEmpty()) {
                continue;
            }

            /*
             * TreeSet naturally checks hotbar slots first because hotbar is 0-8.
             */
            for (int targetIndex : assignedSlots) {
                if (!isMainInventoryIndex(targetIndex)) {
                    continue;
                }

                ItemStack targetStack = mc.thePlayer.inventory.mainInventory[targetIndex];

                /*
                 * If the target already contains this item type, fill it from
                 * other matching stacks if possible.
                 */
                if (targetStack != null && ItemKey.from(targetStack).equals(key)) {
                    int max = Math.min(
                            targetStack.getMaxStackSize(),
                            mc.thePlayer.inventory.getInventoryStackLimit()
                    );

                    if (targetStack.stackSize >= max) {
                        continue;
                    }

                    int sourceIndex = findBestSourceSlotForKey(key, targetIndex);

                    if (sourceIndex == -1) {
                        continue;
                    }

                    ItemStack sourceStack = mc.thePlayer.inventory.mainInventory[sourceIndex];

                    if (clickPickUpPlaceAndReturn(container, sourceIndex, targetIndex)) {
                        chat(
                                EnumChatFormatting.GRAY + "Merged " +
                                        EnumChatFormatting.WHITE + sourceStack.getDisplayName() +
                                        EnumChatFormatting.GRAY + " -> " +
                                        EnumChatFormatting.AQUA + describeInventoryIndex(targetIndex)
                        );
                        return true;
                    }

                    continue;
                }

                /*
                 * Target is empty or blocked by a different item.
                 * Find a matching source stack to move/swap into this target.
                 */
                int sourceIndex = findBestSourceSlotForKey(key, targetIndex);

                if (sourceIndex == -1) {
                    continue;
                }

                ItemStack sourceStack = mc.thePlayer.inventory.mainInventory[sourceIndex];

                if (targetStack == null) {
                    if (clickMoveStack(container, sourceIndex, targetIndex)) {
                        chat(
                                EnumChatFormatting.GRAY + "Moved " +
                                        EnumChatFormatting.WHITE + sourceStack.getDisplayName() +
                                        EnumChatFormatting.GRAY + " -> " +
                                        EnumChatFormatting.AQUA + describeInventoryIndex(targetIndex)
                        );
                        return true;
                    }

                    continue;
                }

                /*
                 * Target is blocked by a different item.
                 * Blocking items are always swapped during a sort operation.
                 */
                if (clickPickUpPlaceAndReturn(container, sourceIndex, targetIndex)) {
                    chat(
                            EnumChatFormatting.GRAY + "Swapped blocker with " +
                                    EnumChatFormatting.WHITE + sourceStack.getDisplayName() +
                                    EnumChatFormatting.GRAY + " -> " +
                                    EnumChatFormatting.AQUA + describeInventoryIndex(targetIndex)
                    );
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Finds a source slot containing the requested item key.
     *
     * Priority:
     * 1. Non-assigned/wrong slots first, because those are usually newly picked
     *    up or shift-clicked stacks.
     * 2. Later assigned slots second, allowing the mod to fill earlier/hotbar
     *    assigned slots before later inventory assigned slots.
     */
    private int findBestSourceSlotForKey(ItemKey key, int targetIndex) {
        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            if (i != targetIndex && stackMatches(i, key) && !isSlotAssignedToKey(key, i)) {
                return i;
            }
        }

        /*
         * Only pull from later slots. This prevents inventory slots from
         * stealing stacks out of earlier/hotbar assigned slots.
         */
        for (int i = targetIndex + 1; i < MAIN_INVENTORY_SIZE; i++) {
            if (stackMatches(i, key)) {
                return i;
            }
        }

        return -1;
    }

    private boolean stackMatches(int invIndex, ItemKey key) {
        ItemStack stack = mc.thePlayer.inventory.mainInventory[invIndex];
        return stack != null && ItemKey.from(stack).equals(key);
    }

    /**
     * Pick up the source stack and drop it into the (empty) target slot.
     */
    private boolean clickMoveStack(Container container, int sourceInvIndex, int targetInvIndex) {
        Slot sourceSlot = findContainerSlotForInventoryIndex(container, sourceInvIndex);
        Slot targetSlot = findContainerSlotForInventoryIndex(container, targetInvIndex);

        if (sourceSlot == null || targetSlot == null) {
            return false;
        }

        leftClick(container, sourceSlot);
        leftClick(container, targetSlot);
        mc.playerController.updateController();

        return true;
    }

    /**
     * Pick up the source stack, click the target, then click the source again.
     *
     * Used for both cases where the target is occupied:
     * - Same item: merges into the target and returns any leftover to the source.
     * - Different item (blocker): swaps, leaving the blocker in the source slot.
     */
    private boolean clickPickUpPlaceAndReturn(Container container, int sourceInvIndex, int targetInvIndex) {
        Slot sourceSlot = findContainerSlotForInventoryIndex(container, sourceInvIndex);
        Slot targetSlot = findContainerSlotForInventoryIndex(container, targetInvIndex);

        if (sourceSlot == null || targetSlot == null) {
            return false;
        }

        leftClick(container, sourceSlot);
        leftClick(container, targetSlot);
        leftClick(container, sourceSlot);
        mc.playerController.updateController();

        return true;
    }

    private void leftClick(Container container, Slot slot) {
        mc.playerController.windowClick(container.windowId, slot.slotNumber, 0, 0, mc.thePlayer);
    }

    private Slot findContainerSlotForInventoryIndex(Container container, int invIndex) {
        for (Object obj : container.inventorySlots) {
            if (!(obj instanceof Slot)) {
                continue;
            }

            Slot slot = (Slot) obj;

            if (slot.inventory == mc.thePlayer.inventory && slot.getSlotIndex() == invIndex) {
                return slot;
            }
        }

        return null;
    }

    private static boolean isMainInventoryIndex(int invIndex) {
        return invIndex >= 0 && invIndex < MAIN_INVENTORY_SIZE;
    }

    /**
     * Adds a slot to an item's assignments.
     *
     * @return true if the slot was not already assigned to that item
     */
    private boolean assignSlot(ItemKey key, int invIndex) {
        TreeSet<Integer> slots = memory.get(key);

        if (slots == null) {
            slots = new TreeSet<Integer>();
            memory.put(key, slots);
        }

        return slots.add(invIndex);
    }

    private boolean isSlotAssignedToKey(ItemKey key, int invIndex) {
        TreeSet<Integer> slots = memory.get(key);

        return slots != null && slots.contains(invIndex);
    }

    private void removeAssignment(ItemKey key, int invIndex) {
        TreeSet<Integer> slots = memory.get(key);

        if (slots == null) {
            return;
        }

        slots.remove(invIndex);

        if (slots.isEmpty()) {
            memory.remove(key);
        }
    }

    private void removeAssignmentsToSlot(int invIndex) {
        Iterator<Map.Entry<ItemKey, TreeSet<Integer>>> iterator = memory.entrySet().iterator();

        while (iterator.hasNext()) {
            TreeSet<Integer> slots = iterator.next().getValue();

            slots.remove(invIndex);

            if (slots.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private Slot getHoveredSlot(GuiContainer gui) {
        try {
            return ReflectionHelper.getPrivateValue(
                    GuiContainer.class,
                    gui,
                    "theSlot",
                    "field_147006_u"
            );
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describeInventoryIndex(int index) {
        if (index >= 0 && index < HOTBAR_SIZE) {
            return "hotbar " + (index + 1);
        }

        if (index >= HOTBAR_SIZE && index < MAIN_INVENTORY_SIZE) {
            int mainIndex = index - HOTBAR_SIZE;
            int row = mainIndex / HOTBAR_SIZE + 1;
            int column = mainIndex % HOTBAR_SIZE + 1;
            return "inventory row " + row + ", slot " + column;
        }

        return "slot " + index;
    }

    private static String plural(int count, String noun) {
        return count == 1 ? noun : noun + "s";
    }

    private static void chat(String message) {
        if (mc.thePlayer == null) {
            return;
        }

        mc.thePlayer.addChatMessage(
                new ChatComponentText(
                        EnumChatFormatting.DARK_GRAY + "[" +
                                EnumChatFormatting.AQUA + "SlotMemory" +
                                EnumChatFormatting.DARK_GRAY + "] " +
                                EnumChatFormatting.RESET +
                                message
                )
        );
    }

    private static final class ItemKey {
        private final int itemId;
        private final int damage;
        private final NBTTagCompound tag;

        private ItemKey(int itemId, int damage, NBTTagCompound tag) {
            this.itemId = itemId;
            this.damage = damage;
            this.tag = tag;
        }

        public static ItemKey from(ItemStack stack) {
            Item item = stack.getItem();

            /*
             * For damageable items, ignore durability damage.
             * For non-damageable items, keep metadata so variants stay distinct.
             */
            int damage = item.isDamageable() ? 0 : stack.getItemDamage();

            NBTTagCompound copiedTag = copyTagIgnoringEnchantments(stack);

            return new ItemKey(
                    Item.getIdFromItem(item),
                    damage,
                    copiedTag
            );
        }

        private static NBTTagCompound copyTagIgnoringEnchantments(ItemStack stack) {
            if (!stack.hasTagCompound()) {
                return null;
            }

            NBTTagCompound copiedTag = (NBTTagCompound) stack.getTagCompound().copy();

            /*
             * Normal enchanted items use "ench".
             * Enchanted books use "StoredEnchantments".
             *
             * RepairCost is not an enchantment, but it is commonly attached by
             * anvil/enchantment workflows and would otherwise make equivalent
             * enchanted tools fail to match.
             */
            copiedTag.removeTag("ench");
            copiedTag.removeTag("StoredEnchantments");
            copiedTag.removeTag("RepairCost");

            if (copiedTag.getKeySet().isEmpty()) {
                return null;
            }

            return copiedTag;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ItemKey)) {
                return false;
            }

            ItemKey o = (ItemKey) other;

            if (this.itemId != o.itemId) {
                return false;
            }

            if (this.damage != o.damage) {
                return false;
            }

            if (this.tag == null && o.tag == null) {
                return true;
            }

            if (this.tag == null || o.tag == null) {
                return false;
            }

            return this.tag.equals(o.tag);
        }

        @Override
        public int hashCode() {
            int result = itemId;
            result = 31 * result + damage;
            result = 31 * result + (tag != null ? tag.hashCode() : 0);
            return result;
        }
    }
}