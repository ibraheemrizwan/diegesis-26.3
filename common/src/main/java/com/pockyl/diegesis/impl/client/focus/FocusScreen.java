package com.pockyl.diegesis.impl.client.focus;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import com.pockyl.diegesis.api.scene.CameraGlance;
import com.pockyl.diegesis.api.client.ScreenOverlay;
import com.pockyl.diegesis.api.scene.InventoryDrawer;
import com.pockyl.diegesis.api.scene.NodeDefinition;
import com.pockyl.diegesis.impl.action.StationAction.Target;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.render.SmoothPass;
import com.pockyl.diegesis.impl.client.scene.NodeInstance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The screen that is open while the player works at a station. It draws no background - the world is the interface.
 * For stations with item slots it shows the player's inventory as the vanilla inventory panel along the bottom (same
 * texture, slots, highlight and tooltips as a vanilla container screen), or in a drawer at a side of the screen when
 * the scene asks for one. Whatever part of that panel is on the screen comes before the scene under it. It routes the
 * mouse to {@link FocusInput}. Closing it (Escape, the inventory key) leaves the station.
 */
public final class FocusScreen extends Screen {
    /** Widest line of a node's tooltip, in GUI pixels. */
    private static final int TOOLTIP_WIDTH = 200;
    /** How far a caption stays from the edge of the screen, in GUI pixels. */
    private static final int CAPTION_MARGIN = 10;

    private static final Identifier PANEL = Identifier.parse("textures/gui/container/generic_54.png");
    private static final int PANEL_WIDTH = 176;
    /** A compact drawer: the gap round its slots, their pitch, how many in a row. */
    private static final int COMPACT_PAD = 3;
    private static final int PITCH = 18;
    private static final int COMPACT_COLUMNS = 9;
    /** Top border (3 px of the chest texture) + the inventory part of it (from the label row to the bottom edge). */
    private static final int BORDER = 3;
    private static final int INVENTORY_V = 125;
    private static final int INVENTORY_HEIGHT = 97;
    private static final int PANEL_HEIGHT = BORDER + INVENTORY_HEIGHT;
    private static final int MAIN_Y = BORDER + 15;
    private static final int HOTBAR_Y = BORDER + 73;
    private static final int BOTTOM_MARGIN = 4;
    private static final int SLOT_HIGHLIGHT = 0x80FFFFFF;
    private static final int LABEL_COLOR = 0x404040;
    /** The drawer's icon: its size along the panel's edge, and how far it sticks out of it. */
    private static final int TAB_LENGTH = 26;
    private static final int TAB_DEPTH = 22;
    /** The top of the minimal drawer inside the panel: it has no label row. */
    private static final int MINIMAL_TOP = MAIN_Y - 5;
    private static final int METER_WIDTH = 3;
    private static final int METER_HEIGHT = 26;
    /** The arrow at an edge a glance goes to: half its width, its gap from the edge, how faint it is far from the cursor. */
    private static final int HINT_HALF = 7;
    private static final int HINT_INSET = 5;
    private static final float HINT_FAR = 0.35F;

    private static final int OFF_SCREEN = -10000;
    private static final float OVERLAY_Z = 400.0F;
    /** The panel's place in GUI pixels; NO_PANEL when no panel is shown. */
    private static final int NO_PANEL = Integer.MIN_VALUE;
    private static int panelLeft = NO_PANEL;
    private static int panelTop = NO_PANEL;
    /** The panel's size, and how much of its top is not drawn (the minimal drawer has no label row). */
    private static int panelWidth = PANEL_WIDTH;
    private static int panelHeight = PANEL_HEIGHT;
    private static int panelSkip;
    /** The inventory slots a compact drawer shows, in their order on it, and the station they were picked for. */
    private static final List<Integer> compactSlots = new ArrayList<>();
    private static ClientStation compactFor;
    private long lastFrame = Util.getNanos();
    /**
     * Movement and arrow keys that are down: a key held down repeats, and only its first press goes over to a neighbour. Keys
     * already down when the screen opens count as held, so a player walking up to a station does not fly on at once.
     */
    private final Set<Integer> heldKeys = new HashSet<>();

    /** Set by the development test driver, which cannot press real keys. */
    @ApiStatus.Internal
    public static boolean forceShift;
    /** Set by the development test driver: the mouse position to use instead of the real cursor's, or null. */
    @ApiStatus.Internal
    public static double[] forcedMouse;
    /** Holds Control down for the development test driver. */
    @ApiStatus.Internal
    public static boolean forceControl;

    /** What the mod laid over the scene; it has the mouse while it is open. */
    private static ScreenOverlay overlay;

    /** {@code StationView.showOverlay}. */
    public static void showOverlay(ScreenOverlay value) {
        if (overlay != null && overlay != value) {
            overlay.onClosed();
        }
        overlay = value;
    }

    /** Whether an overlay has the mouse. */
    static boolean isCovered() {
        return overlay != null;
    }

    public FocusScreen() {
        super(Component.translatable("diegesis.focus"));
    }

    /** Whether a GUI point lies over the world (not over the inventory panel). */
    static boolean isOverWorld(double mouseX, double mouseY) {
        return panelTop == NO_PANEL || mouseY < panelY() || mouseY >= panelTop + panelHeight || mouseX < panelLeft
                || mouseX >= panelLeft + panelWidth;
    }

    /** The GUI centre of an inventory slot; for the development test driver. */
    @ApiStatus.Internal
    public static int[] slotCenter(int slot) {
        InventoryDrawer drawer = drawer();
        if (drawer != null && drawer.compact()) {
            int index = compactSlots.indexOf(slot);
            if (index < 0) {
                return new int[] {OFF_SCREEN, OFF_SCREEN};
            }
            return new int[] {panelLeft + COMPACT_PAD + index % COMPACT_COLUMNS * PITCH + PITCH / 2,
                    panelTop + COMPACT_PAD + index / COMPACT_COLUMNS * PITCH + PITCH / 2};
        }
        int x = panelLeft + 8 + (slot % 9) * 18 + 8;
        int y = panelTop + (slot < 9 ? HOTBAR_Y : MAIN_Y + (slot / 9 - 1) * 18) + 8;
        return new int[] {x, y};
    }

    /** Whether the inventory slot is on show now: a stack got there can fly into it. */
    static boolean isSlotShown(int slot) {
        if (!showsInventory() || panelTop == NO_PANEL || slotCenter(slot)[0] == OFF_SCREEN) {
            return false;
        }
        return drawer() == null || FocusInput.get().drawer().shown() >= 1.0F;
    }

    /** What the player got at the station, flying in; a stack whose slot is not on show stands next to the drawer for a moment. */
    private void renderGains(GuiGraphicsExtractor graphics, FocusInput input) {
        float asideX = width / 2.0F;
        float asideY = height - 24.0F;
        if (panelTop != NO_PANEL && panelWidth > 0 && showsInventory()) {
            asideX = panelLeft + panelWidth + 14.0F;
            asideY = panelTop + panelHeight / 2.0F;
        }
        input.gains().render(graphics, font, asideX, asideY);
    }

    /** The panel shows for stations with items whose scene does not lay the inventory out itself, and for every drawer. */
    private static boolean showsInventory() {
        ClientStation station = FocusController.get().station();
        if (drawer() != null) {
            return true;
        }
        return station != null && station.type().schema().hasItems() && FocusController.get().options().inventoryPanel()
                && (station.scene() == null || station.scene().inventoryLayout() == null);
    }

    /** The scene's inventory drawer, or null. */
    private static InventoryDrawer drawer() {
        ClientStation station = FocusController.get().station();
        return station == null || station.scene() == null ? null : station.scene().definition().inventoryDrawer().orElse(null);
    }

    /** Where the panel's visible part starts. */
    private static int panelY() {
        return panelTop + panelSkip;
    }

    @Override
    protected void init() {
        layout();
        heldKeys.clear();
        var options = Minecraft.getInstance().options;
        List<Integer> keys = new ArrayList<>(List.of(InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT, InputConstants.KEY_UP, InputConstants.KEY_DOWN));
        for (var mapping : List.of(options.keyLeft, options.keyRight, options.keyUp, options.keyDown)) {
            InputConstants.Key bound = InputConstants.getKey(mapping.saveString());
            if (bound.getType() == InputConstants.Type.KEYBOARD && bound.getValue() != InputConstants.UNKNOWN.getValue()) {
                keys.add(bound.getValue());
            }
        }
        for (int key : keys) {
            if (InputConstants.isKeyDown(key)) {
                heldKeys.add(key);
            }
        }
    }

    private void layout() {
        InventoryDrawer drawer = drawer();
        panelWidth = PANEL_WIDTH;
        panelHeight = PANEL_HEIGHT;
        panelSkip = drawer != null && drawer.style() == InventoryDrawer.Style.MINIMAL ? MINIMAL_TOP : 0;
        if (drawer != null && drawer.compact()) {
            int rows = (compactSlots.size() + COMPACT_COLUMNS - 1) / COMPACT_COLUMNS;
            panelWidth = compactSlots.isEmpty() ? 0 : Math.min(compactSlots.size(), COMPACT_COLUMNS) * PITCH + 2 * COMPACT_PAD;
            panelHeight = compactSlots.isEmpty() ? 0 : rows * PITCH + 2 * COMPACT_PAD;
            panelSkip = 0;
        }
        if (drawer != null) {
            layoutDrawer(drawer, FocusInput.get().drawer().shown());
        } else if (showsInventory()) {
            panelLeft = (width - PANEL_WIDTH) / 2;
            panelTop = height - BOTTOM_MARGIN - PANEL_HEIGHT;
        } else {
            panelLeft = NO_PANEL;
            panelTop = NO_PANEL;
        }
    }

    /** Places the panel for a drawer {@code out} of the way out: from just past the screen's edge to its place. */
    private void layoutDrawer(InventoryDrawer drawer, float out) {
        int visibleHeight = panelHeight - panelSkip;
        int hidden = panelSkip;
        int margin = Math.round(drawer.margin());
        int acrossX = Math.round((width - panelWidth) * drawer.align());
        int acrossY = Math.round((height - visibleHeight) * drawer.align()) - hidden;
        switch (drawer.side()) {
            case LEFT -> {
                panelLeft = Math.round(-panelWidth + (panelWidth + margin) * out);
                panelTop = acrossY;
            }
            case RIGHT -> {
                panelLeft = Math.round(width - (panelWidth + margin) * out);
                panelTop = acrossY;
            }
            case TOP -> {
                panelLeft = acrossX;
                panelTop = Math.round(-panelHeight + (visibleHeight + margin) * out);
            }
            case BOTTOM -> {
                panelLeft = acrossX;
                panelTop = Math.round(height - hidden - (visibleHeight + margin) * out);
            }
        }
    }

    /** The icon's box: x, y, width, height, on the panel's edge that faces the middle of the screen. */
    private static int[] tab(InventoryDrawer drawer) {
        int top = panelY();
        int visibleHeight = panelTop + panelHeight - top;
        return switch (drawer.side()) {
            case LEFT -> new int[] {panelLeft + panelWidth, top + (visibleHeight - TAB_LENGTH) / 2, TAB_DEPTH, TAB_LENGTH};
            case RIGHT -> new int[] {panelLeft - TAB_DEPTH, top + (visibleHeight - TAB_LENGTH) / 2, TAB_DEPTH, TAB_LENGTH};
            case TOP -> new int[] {panelLeft + (panelWidth - TAB_LENGTH) / 2, panelTop + panelHeight, TAB_LENGTH, TAB_DEPTH};
            case BOTTOM -> new int[] {panelLeft + (panelWidth - TAB_LENGTH) / 2, top - TAB_DEPTH, TAB_LENGTH, TAB_DEPTH};
        };
    }

    private static boolean onTab(InventoryDrawer drawer, double mouseX, double mouseY) {
        if (drawer.alwaysOpen()) {
            return false;
        }
        int[] tab = tab(drawer);
        return mouseX >= tab[0] && mouseX < tab[0] + tab[2] && mouseY >= tab[1] && mouseY < tab[1] + tab[3];
    }

    /** Whether the cursor is where the drawer should be out: the strip along its side, the icon, the panel itself. */
    private boolean atDrawer(InventoryDrawer drawer, double mouseX, double mouseY) {
        float zone = drawer.hoverZone();
        boolean strip = zone > 0.0F && switch (drawer.side()) {
            case LEFT -> mouseX < zone;
            case RIGHT -> mouseX >= width - zone;
            case TOP -> mouseY < zone;
            case BOTTOM -> mouseY >= height - zone;
        };
        return strip || onTab(drawer, mouseX, mouseY) || !isOverWorld(mouseX, mouseY);
    }

    private void updateDrawer(InventoryDrawer drawer, double mouseX, double mouseY) {
        long now = Util.getNanos();
        float dt = Math.min((now - lastFrame) / 1.0e9F, 0.1F);
        lastFrame = now;
        FocusInput input = FocusInput.get();
        boolean wasOpen = input.drawer().isOpen();
        input.drawer().update(drawer, dt, atDrawer(drawer, mouseX, mouseY), input.isHoldingObject());
        if (input.drawer().isOpen() != wasOpen) {
            ClientStation station = FocusController.get().station();
            if (station != null) {
                station.onEvent(new com.pockyl.diegesis.api.event.SceneEvent.InventoryDrawer(input.drawer().isOpen()));
            }
        }
        layoutDrawer(drawer, input.drawer().shown());
    }

    /**
     * Which slots a compact drawer shows: the stacks the station's filter lets through, new ones at the end. What no longer
     * shows leaves only while nothing is carried and the cursor is away, and a stack on the cursor gets an empty slot.
     */
    private static void pickCompact(double mouseX, double mouseY) {
        InventoryDrawer drawer = drawer();
        ClientStation station = FocusController.get().station();
        LocalPlayer player = Minecraft.getInstance().player;
        if (drawer == null || !drawer.compact() || station == null || player == null) {
            return;
        }
        if (compactFor != station) {
            compactFor = station;
            compactSlots.clear();
        }
        Inventory inventory = player.getInventory();
        java.util.function.Predicate<ItemStack> filter = station.inventoryFilter();
        boolean carrying = FocusInput.get().carried() != null || !player.inventoryMenu.getCarried().isEmpty();
        if (!carrying && isOverWorld(mouseX, mouseY)) {
            compactSlots.removeIf(slot -> inventory.getItem(slot).isEmpty() || !filter.test(inventory.getItem(slot)));
        }
        // The hotbar first, as the player knows it, then the rest.
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && filter.test(stack) && !compactSlots.contains(slot)) {
                compactSlots.add(slot);
            }
        }
        if (carrying && compactSlots.stream().noneMatch(slot -> inventory.getItem(slot).isEmpty())) {
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                if (inventory.getItem(slot).isEmpty()) {
                    compactSlots.add(slot);
                    break;
                }
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int realMouseX, int realMouseY, float partialTick) {
        int pointerX = forcedMouse != null ? (int) forcedMouse[0] : realMouseX;
        int pointerY = forcedMouse != null ? (int) forcedMouse[1] : realMouseY;
        // Under an overlay the scene sees no mouse at all.
        renderScreen(graphics, overlay != null ? OFF_SCREEN : pointerX, overlay != null ? OFF_SCREEN : pointerY);
        if (overlay != null) {
            graphics.pose().pushMatrix();
            graphics.nextStratum();
            overlay.render(graphics, pointerX, pointerY, partialTick);
            graphics.pose().popMatrix();
        }
    }

    private void renderScreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        pickCompact(mouseX, mouseY);
        layout();
        InventoryDrawer drawer = drawer();
        if (drawer != null) {
            updateDrawer(drawer, mouseX, mouseY);
        }
        FocusInput input = FocusInput.get();
        int hoveredSlot = slotAt(mouseX, mouseY);
        input.mouseMoved(mouseX, mouseY, hoveredSlot);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        renderGlanceHints(graphics, input, mouseX, mouseY);
        if (drawer != null) {
            renderDrawer(graphics, drawer, player.getInventory(), hoveredSlot, mouseX, mouseY);
        } else if (panelTop != NO_PANEL) {
            renderPanel(graphics, player.getInventory(), hoveredSlot);
        }
        renderGains(graphics, input);
        renderMeter(graphics, input, mouseX, mouseY);
        renderCaption(graphics);
        FocusInput.Carried carried = input.carried();
        if (carried != null) {
            if (carried.preview() != null) {
                // The stack itself stands on the surface as a preview; the cursor only says how many are left in the hand.
                graphics.pose().pushMatrix();
                graphics.nextStratum();
                graphics.itemDecorations(font, carried.stack(), mouseX - 8, mouseY - 8);
                graphics.pose().popMatrix();
            } else if (!carried.inWorld()) {
                graphics.pose().pushMatrix();
                graphics.nextStratum();
                graphics.item(carried.stack(), mouseX - 8, mouseY - 8);
                graphics.itemDecorations(font, carried.stack(), mouseX - 8, mouseY - 8);
                graphics.pose().popMatrix();
            }
            return;
        }
        if (input.isBusy()) {
            return;
        }
        if (drawer != null && onTab(drawer, mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(font, Component.translatable("container.inventory"), mouseX, mouseY);
            return;
        }
        int inventorySlot = hoveredSlot >= 0 ? hoveredSlot : input.hoveredInventorySlot();
        if (inventorySlot >= 0) {
            ItemStack stack = player.getInventory().getItem(inventorySlot);
            if (!stack.isEmpty()) {
                graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
            }
            return;
        }
        NodeInstance node = input.hovered();
        ClientStation station = FocusController.get().station();
        if (node != null && station != null) {
            ItemStack shown = itemOf(station, node.definition());
            net.minecraft.network.chat.Component text = node.tooltip();
            if (!shown.isEmpty()) {
                graphics.setTooltipForNextFrame(font, shown, mouseX, mouseY);
            } else if (text != null && !text.getString().isEmpty()) {
                // Line breaks in the text start new lines; long lines wrap.
                graphics.setTooltipForNextFrame(font, font.split(text, TOOLTIP_WIDTH), mouseX, mouseY);
            }
        }
    }

    /** Arrows at the edges the look can go to, and at the one it comes back from while it is away. */
    /** The station's line at the edge of the screen ({@code StationView.showCaption}). */
    private void renderCaption(GuiGraphicsExtractor graphics) {
        ClientStation station = FocusController.get().station();
        net.minecraft.network.chat.Component text = station == null ? null : station.caption();
        if (text == null || station.scene() == null) {
            return;
        }
        com.pockyl.diegesis.api.scene.CaptionOptions look = station.scene().definition().captions();
        int alpha = Math.round(station.captionAlpha() * 255.0F);
        if (alpha < 8) {
            return;
        }
        int textWidth = font.width(text);
        int x = (width - textWidth) / 2;
        int y = look.edge() == com.pockyl.diegesis.api.scene.CaptionOptions.Edge.TOP ? CAPTION_MARGIN : height - CAPTION_MARGIN - font.lineHeight;
        int background = (look.background() >>> 24) * alpha / 255;
        if (background > 0) {
            graphics.fill(x - 6, y - 4, x + textWidth + 6, y + font.lineHeight + 3, background << 24 | look.background() & 0xFFFFFF);
        }
        graphics.text(font, text, x, y, ((look.color() >>> 24) * alpha / 255) << 24 | look.color() & 0xFFFFFF, true);
    }

    private void renderGlanceHints(GuiGraphicsExtractor graphics, FocusInput input, int mouseX, int mouseY) {
        ClientStation station = FocusController.get().station();
        if (station == null || station.scene() == null || !FocusController.get().isInteractive() || input.isHoldingObject()) {
            return;
        }
        float u = (float) mouseX / width;
        float v = (float) mouseY / height;
        Glance look = input.look();
        CameraGlance current = look.current();
        float away = look.amount();
        for (CameraGlance glance : station.scene().definition().camera().glances()) {
            if (!glance.hint() || glance.edge().isEmpty()) {
                continue;
            }
            if (current == null) {
                renderHint(graphics, glance.hintColor(), glance.edge().get(), glance.zone(), 1.0F - away, HINT_FAR, u, v);
            } else if (current == glance && glance.backZone() > 0.0F) {
                renderHint(graphics, glance.hintColor(), Glance.opposite(glance.edge().get()), glance.backZone(), away, HINT_FAR, u, v);
            }
        }
        if (!input.mayGoOver()) {
            return;
        }
        // A neighbour's arrow shows only as the cursor comes near its edge: a kitchen is not to be full of arrows.
        for (Neighbors.Neighbor neighbor : input.neighbors().found().values()) {
            if (neighbor.rules().hint()) {
                renderHint(graphics, neighbor.rules().hintColor(), neighbor.edge(), neighbor.rules().zone(), 1.0F - away, 0.0F, u, v);
            }
        }
    }

    private void renderHint(GuiGraphicsExtractor graphics, int hintColor, InventoryDrawer.Side edge, float zone, float shown, float faint, float u,
            float v) {
        // Faint across the screen ({@code faint}), full at the strip: it answers the cursor coming closer.
        float near = 1.0F - Math.min(1.0F, Math.max(0.0F, (Glance.distance(edge, u, v) - zone) / 0.3F));
        float strength = shown * (faint + (1.0F - faint) * near);
        int alpha = Math.round((hintColor >>> 24) * strength);
        if (alpha < 4) {
            return;
        }
        int color = alpha << 24 | hintColor & 0xFFFFFF;
        boolean horizontal = edge == InventoryDrawer.Side.TOP || edge == InventoryDrawer.Side.BOTTOM;
        int length = horizontal ? width : height;
        int across = horizontal ? height : width;
        boolean far = edge == InventoryDrawer.Side.BOTTOM || edge == InventoryDrawer.Side.RIGHT;
        for (int i = -HINT_HALF; i <= HINT_HALF; i++) {
            // A flat V with its point towards the edge, two pixels thick.
            int depth = HINT_INSET + Math.abs(i) / 2;
            int from = far ? across - depth - 2 : depth;
            int along = length / 2 + i;
            if (horizontal) {
                graphics.fill(along, from, along + 1, from + 2, color);
            } else {
                graphics.fill(from, along, from + 2, along + 1, color);
            }
        }
    }

    private void renderDrawer(GuiGraphicsExtractor graphics, InventoryDrawer drawer, Inventory inventory, int hoveredSlot, int mouseX, int mouseY) {
        // Over everything the world drew; the items' own depth stays above it.
        graphics.pose().pushMatrix();
        graphics.nextStratum();
        if (FocusInput.get().drawer().shown() > 0.0F) {
            if (drawer.style() == InventoryDrawer.Style.MINIMAL || drawer.compact()) {
                renderMinimal(graphics, drawer, inventory, hoveredSlot);
            } else {
                renderPanel(graphics, inventory, hoveredSlot);
            }
        }
        renderTab(graphics, drawer, mouseX, mouseY);
        graphics.pose().popMatrix();
    }

    private void renderTab(GuiGraphicsExtractor graphics, InventoryDrawer drawer, int mouseX, int mouseY) {
        if (drawer.alwaysOpen()) {
            return;
        }
        int[] tab = tab(drawer);
        boolean lit = onTab(drawer, mouseX, mouseY) || FocusInput.get().drawer().isPinned();
        boolean minimal = drawer.style() == InventoryDrawer.Style.MINIMAL;
        int background = minimal ? drawer.background() : 0xFFC6C6C6;
        graphics.fill(tab[0], tab[1], tab[0] + tab[2], tab[1] + tab[3], background);
        if (minimal) {
            if (lit) {
                graphics.fill(tab[0], tab[1], tab[0] + tab[2], tab[1] + tab[3], 0x30FFFFFF);
            }
        } else {
            // The vanilla panel's bevel: light from the top left.
            graphics.fill(tab[0], tab[1], tab[0] + tab[2], tab[1] + 1, 0xFFFFFFFF);
            graphics.fill(tab[0], tab[1], tab[0] + 1, tab[1] + tab[3], 0xFFFFFFFF);
            graphics.fill(tab[0], tab[1] + tab[3] - 1, tab[0] + tab[2], tab[1] + tab[3], 0xFF555555);
            graphics.fill(tab[0] + tab[2] - 1, tab[1], tab[0] + tab[2], tab[1] + tab[3], 0xFF555555);
            if (lit) {
                graphics.fill(tab[0] + 1, tab[1] + 1, tab[0] + tab[2] - 1, tab[1] + tab[3] - 1, 0x40FFFFFF);
            }
        }
        int x = tab[0] + (tab[2] - 16) / 2;
        int y = tab[1] + (tab[3] - 16) / 2;
        if (drawer.iconTexture().isPresent()) {
            graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,drawer.iconTexture().get(),x,y,0.0F,0.0F,16,16,16,16);
        } else {
            graphics.item(new ItemStack(drawer.icon()), x, y);
        }
    }

    private void renderMinimal(GuiGraphicsExtractor graphics, InventoryDrawer drawer, Inventory inventory, int hoveredSlot) {
        graphics.fill(panelLeft, panelY(), panelLeft + panelWidth, panelTop + panelHeight, drawer.background());
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            int[] center = slotCenter(slot);
            if (center[0] == OFF_SCREEN) {
                continue;
            }
            graphics.fill(center[0] - 8, center[1] - 8, center[0] + 8, center[1] + 8, drawer.slotColor());
        }
        renderSlots(graphics, inventory, hoveredSlot);
    }

    /** Next to the cursor while pouring: how full the tank being poured into is. */
    private void renderMeter(GuiGraphicsExtractor graphics, FocusInput input, int mouseX, int mouseY) {
        FocusInput.Meter meter = input.pourMeter();
        if (meter == null) {
            return;
        }
        int x = mouseX + 12;
        int y = mouseY - METER_HEIGHT / 2;
        int fill = Math.round(meter.fill() * METER_HEIGHT);
        graphics.fill(x - 1, y - 1, x + METER_WIDTH + 1, y + METER_HEIGHT + 1, 0xA0000000);
        graphics.fill(x, y + METER_HEIGHT - fill, x + METER_WIDTH, y + METER_HEIGHT, 0xFF000000 | meter.color());
    }

    private void renderPanel(GuiGraphicsExtractor graphics, Inventory inventory, int hoveredSlot) {
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,PANEL,panelLeft,panelTop,0,0,PANEL_WIDTH,BORDER,256,256);
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,PANEL,panelLeft,panelTop+BORDER,0,INVENTORY_V,PANEL_WIDTH,INVENTORY_HEIGHT,256,256);
        graphics.text(font, Component.translatable("container.inventory"), panelLeft + 8, panelTop + BORDER + 3, LABEL_COLOR, false);
        renderSlots(graphics, inventory, hoveredSlot);
    }

    private void renderSlots(GuiGraphicsExtractor graphics, Inventory inventory, int hoveredSlot) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            int[] center = slotCenter(slot);
            if (center[0] == OFF_SCREEN) {
                continue;
            }
            int x = center[0] - 8;
            int y = center[1] - 8;
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                // A stack that has just landed here bounces.
                float scale = FocusInput.get().gains().slotScale(slot);
                graphics.pose().pushMatrix();
                graphics.pose().translate(center[0], center[1]);
                graphics.pose().scale(scale, scale);
                graphics.item(stack, -8, -8);
                graphics.itemDecorations(font, stack, -8, -8);
                graphics.pose().popMatrix();
            }
            if (slot == hoveredSlot || FocusInput.get().isSpreadTarget(new Target.Inventory(slot))) {
                graphics.fillGradient( x, y, x + 16, y + 16, SLOT_HIGHLIGHT,
                        SLOT_HIGHLIGHT);
            }
        }
    }

    private static ItemStack itemOf(ClientStation station, NodeDefinition node) {
        if (node.slot().isPresent()) {
            return station.state().getItem(node.slot().get().slot());
        }
        if (node.output().isPresent()) {
            return station.state().getOutput(node.output().get().output());
        }
        return ItemStack.EMPTY;
    }

    /** The inventory slot at a GUI position; -2 over the panel but not on a slot; -1 outside the panel. */
    private static int slotAt(double mouseX, double mouseY) {
        InventoryDrawer drawer = drawer();
        if (drawer != null && onTab(drawer, mouseX, mouseY)) {
            return -2;
        }
        if (isOverWorld(mouseX, mouseY)) {
            return -1;
        }
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            int[] center = slotCenter(slot);
            if (mouseX >= center[0] - 9 && mouseX < center[0] + 9 && mouseY >= center[1] - 9 && mouseY < center[1] + 9) {
                return slot;
            }
        }
        return -2;
    }

    static boolean shift() {
        return forceShift || Minecraft.getInstance().hasShiftDown();
    }

    static boolean control() {
        return forceControl || Minecraft.getInstance().hasControlDown();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,boolean doubleClick) {
        double mouseX=event.x(),mouseY=event.y();
        int button=legacyButton(event.button());
        if (overlay != null) {
            if (!overlay.mouseClicked(mouseX, mouseY, button)) {
                showOverlay(null);
            }
            return true;
        }
        InventoryDrawer drawer = drawer();
        if (drawer != null && onTab(drawer, mouseX, mouseY) && !FocusInput.get().isBusy()) {
            FocusInput.get().drawer().togglePin();
            return true;
        }
        if (button == 0 || button == 1) {
            FocusInput.get().press(mouseX, mouseY, button, shift(), slotAt(mouseX, mouseY));
        }
        return true;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,double dragX,double dragY) {
        double mouseX=event.x(),mouseY=event.y();int button=legacyButton(event.button());
        FocusInput.get().drag(mouseX, mouseY, slotAt(mouseX, mouseY));
        return true;
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        double mouseX=event.x(),mouseY=event.y();int button=legacyButton(event.button());
        FocusInput.get().release(mouseX, mouseY, button, slotAt(mouseX, mouseY));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double amount) {
        if (overlay != null) {
            overlay.mouseScrolled(mouseX, mouseY, amount);
            return true;
        }
        FocusInput.get().scroll(mouseX, mouseY, amount);
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        FocusInput.get().mouseMoved(mouseX, mouseY, slotAt(mouseX, mouseY));
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode=event.key(),scanCode=event.keycode(),modifiers=event.modifiers();
        boolean inventoryKey = Minecraft.getInstance().options.keyInventory.matches(new net.minecraft.client.input.KeyEvent(keyCode,scanCode,0));
        if (overlay != null && (keyCode == InputConstants.KEY_ESCAPE || inventoryKey)) {
            showOverlay(null);
            return true;
        }
        if (Minecraft.getInstance().options.keyInventory.matches(new net.minecraft.client.input.KeyEvent(keyCode,scanCode,0))) {
            onClose();
            return true;
        }
        if (keyCode == InputConstants.KEY_TAB && FocusInput.get().toggleInventory()) {
            return true;
        }
        InventoryDrawer.Side edge = edgeOf(keyCode, scanCode);
        // A repeat of a held key is swallowed: two quick presses, the second held a moment, went over twice.
        if (edge != null && !heldKeys.add(keyCode)) {
            return true;
        }
        if (edge != null && FocusInput.get().goOverByKey(edge)) {
            return true;
        }
        // Escape takes back what is on top first: a pinned drawer, then the station.
        if (keyCode == InputConstants.KEY_ESCAPE && drawer() != null && FocusInput.get().drawer().isPinned()) {
            FocusInput.get().drawer().setPinned(false);
            return true;
        }
        return super.keyPressed(event);
    }

    /** The edge of the screen a movement key or an arrow key points to, or null. */
    private static InventoryDrawer.Side edgeOf(int keyCode, int scanCode) {
        var options = Minecraft.getInstance().options;
        if (keyCode == InputConstants.KEY_LEFT || options.keyLeft.matches(new net.minecraft.client.input.KeyEvent(keyCode,scanCode,0))) {
            return InventoryDrawer.Side.LEFT;
        }
        if (keyCode == InputConstants.KEY_RIGHT || options.keyRight.matches(new net.minecraft.client.input.KeyEvent(keyCode,scanCode,0))) {
            return InventoryDrawer.Side.RIGHT;
        }
        if (keyCode == InputConstants.KEY_UP || options.keyUp.matches(new net.minecraft.client.input.KeyEvent(keyCode,scanCode,0))) {
            return InventoryDrawer.Side.TOP;
        }
        if (keyCode == InputConstants.KEY_DOWN || options.keyDown.matches(new net.minecraft.client.input.KeyEvent(keyCode,scanCode,0))) {
            return InventoryDrawer.Side.BOTTOM;
        }
        return null;
    }

    @Override
    public void onClose() {
        FocusController.get().requestExit();
    }

    @Override
    public boolean keyReleased(net.minecraft.client.input.KeyEvent event) {
        int keyCode=event.key(),scanCode=event.keycode(),modifiers=event.modifiers();
        heldKeys.remove(keyCode);
        return super.keyReleased(event);
    }

    @Override
    public void removed() {
        SmoothPass.release();
        showOverlay(null);
        panelLeft = NO_PANEL;
        panelTop = NO_PANEL;
    }
    /** Keep the public station interaction protocol's left=0/right=1 button numbering. */
    private static int legacyButton(int button) { return button==1?0:button==3?1:button; }
}
