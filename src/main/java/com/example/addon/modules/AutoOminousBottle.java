package com.example.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.BossBarS2CPacket;
import net.minecraft.screen.slot.SlotActionType;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;

import static com.example.addon.SeppeAutoBottleAddon.CATEGORY;


public class AutoOminousBottle extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Ticks to wait after Raid Victory before acting.")
        .defaultValue(20)
        .min(0).sliderMax(200)
        .build()
    );

    private final Setting<Integer> moveDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("move-delay-ticks")
        .description("Extra delay after moving the bottle into the hotbar, before selecting/drinking it. Only applies if it wasn't already in hand or hotbar.")
        .defaultValue(10)
        .min(0).sliderMax(100)
        .build()
    );

    private final Setting<Integer> drinkHoldTicks = sgGeneral.add(new IntSetting.Builder()
        .name("drink-hold-ticks")
        .description("How long to hold the use key - matches the potion drink animation.")
        .defaultValue(33)
        .min(20).sliderMax(60)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> randomize = sgGeneral.add(new BoolSetting.Builder()
        .name("randomize")
        .description("Add random variance to every delay so timing isn't identical each raid.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delayVariance = sgGeneral.add(new IntSetting.Builder()
        .name("delay-variance")
        .description("Max ticks randomly added/subtracted from delay-ticks.")
        .defaultValue(10)
        .min(0).sliderMax(100)
        .visible(randomize::get)
        .build()
    );

    private final Setting<Integer> moveDelayVariance = sgGeneral.add(new IntSetting.Builder()
        .name("move-delay-variance")
        .description("Max ticks randomly added/subtracted from move-delay-ticks.")
        .defaultValue(5)
        .min(0).sliderMax(50)
        .visible(randomize::get)
        .build()
    );

    private final Setting<Integer> drinkHoldVariance = sgGeneral.add(new IntSetting.Builder()
        .name("drink-hold-variance")
        .description("Max ticks randomly added to drink-hold-ticks (never subtracted below the potion's real drink time).")
        .defaultValue(6)
        .min(0).sliderMax(30)
        .visible(randomize::get)
        .build()
    );

    private static final String VICTORY_KEY = "event.minecraft.raid.victory.full";

    // state machine
    private int waitTimer = -1;      // counting down after victory detected
    private int moveWaitTimer = -1;  // counting down after a real click-move, before selecting+drinking
    private int holdTimer = -1;      // counting down while holding use key

    private int jitter(int base, int variance, int floor) {
        if (!randomize.get() || variance <= 0) return base;
        int offset = ThreadLocalRandom.current().nextInt(-variance, variance + 1);
        return Math.max(floor, base + offset);
    }

    public AutoOminousBottle() {
        super(CATEGORY, "auto-ominous-bottle", "Auto-drinks an Ominous Bottle after Raid Victory.");
    }

    @Override
    public void onDeactivate() {
        releaseUseKey();
        waitTimer = -1;
        moveWaitTimer = -1;
        holdTimer = -1;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null) return;
        if (!(event.packet instanceof BossBarS2CPacket packet)) return;

        try {
            Field actionField = packet.getClass().getDeclaredField("action");
            actionField.setAccessible(true);
            Object action = actionField.get(packet);

            boolean alreadyRunning = waitTimer != -1 || moveWaitTimer != -1 || holdTimer != -1;

            if (!alreadyRunning && action != null && action.toString().contains(VICTORY_KEY)) {
                waitTimer = jitter(delayTicks.get(), delayVariance.get(), 0);
                if (chatFeedback.get()) info("Raid victory detected");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Phase 1: waiting after victory
        if (waitTimer == 0) {
            waitTimer = -1;
            beginSequence();
        } else if (waitTimer > 0) {
            waitTimer--;
        }

        // Phase 1.5: waiting after a real click-move before selecting + drinking
        if (moveWaitTimer == 0) {
            moveWaitTimer = -1;
            selectAndDrink();
        } else if (moveWaitTimer > 0) {
            moveWaitTimer--;
        }

        // Phase 2: holding the use key through the drink animation
        if (holdTimer == 0) {
            releaseUseKey();
            holdTimer = -1;
            if (chatFeedback.get()) info("Drank an Ominous Bottle.");
        } else if (holdTimer > 0) {
            holdTimer--;
        }
    }

    private void beginSequence() {
        // Already in hand?
        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand.getItem() == Items.OMINOUS_BOTTLE) {
            startDrinking();
            return;
        }

        // Already in hotbar somewhere?
        int hotbarSlot = findInHotbar();
        if (hotbarSlot != -1) {
            selectHotbarSlot(hotbarSlot);
            moveWaitTimer = jitter(moveDelayTicks.get(), moveDelayVariance.get(), 0);
            return;
        }

        // In main inventory only - do a real click-move into the currently selected hotbar slot
        int invSlot = findInMainInventory();
        if (invSlot == -1) {
            if (chatFeedback.get()) info("No Ominous Bottle found, skipping.");
            return;
        }

        int targetHotbar = mc.player.getInventory().selectedSlot;
        moveToHotbarLegit(invSlot, targetHotbar);
        moveWaitTimer = jitter(moveDelayTicks.get(), moveDelayVariance.get(), 0);
    }

    private void selectAndDrink() {
        int hotbarSlot = findInHotbar();
        if (hotbarSlot == -1) {
            if (chatFeedback.get()) info("Bottle move failed, aborting.");
            return;
        }
        selectHotbarSlot(hotbarSlot);
        startDrinking();
    }

    private void startDrinking() {
        mc.options.useKey.setPressed(true);
        // hold-time jitter only ever adds ticks, never subtracts below the real drink duration
        int extra = randomize.get() && drinkHoldVariance.get() > 0
            ? ThreadLocalRandom.current().nextInt(0, drinkHoldVariance.get() + 1)
            : 0;
        holdTimer = drinkHoldTicks.get() + extra;
    }

    private void releaseUseKey() {
        mc.options.useKey.setPressed(false);
    }

    private void selectHotbarSlot(int hotbarIndex) {
        mc.player.getInventory().selectedSlot = hotbarIndex;
        mc.getNetworkHandler().sendPacket(
            new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(hotbarIndex)
        );
    }

    private int hotbarToScreenSlot(int hotbarIndex) {
        return 36 + hotbarIndex;
    }

    private int findInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isBottle(stack)) return i;
        }
        return -1;
    }

    private int findInMainInventory() {
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isBottle(stack)) return i;
        }
        return -1;
    }

    private boolean isBottle(ItemStack stack) {
        return stack != null && stack.getItem() == Items.OMINOUS_BOTTLE;
    }


    private void moveToHotbarLegit(int fromInvSlot, int toHotbarIndex) {
        int syncId = mc.player.currentScreenHandler.syncId;
        int toScreenSlot = hotbarToScreenSlot(toHotbarIndex);

        // Pick up the stack from its inventory slot
        mc.interactionManager.clickSlot(syncId, fromInvSlot, 0, SlotActionType.PICKUP, mc.player);
        // Place it into the target hotbar slot (swaps whatever was there back to fromInvSlot)
        mc.interactionManager.clickSlot(syncId, toScreenSlot, 0, SlotActionType.PICKUP, mc.player);

        if (chatFeedback.get()) info("Moving Ominous Bottle to hotbar...");
    }
}
