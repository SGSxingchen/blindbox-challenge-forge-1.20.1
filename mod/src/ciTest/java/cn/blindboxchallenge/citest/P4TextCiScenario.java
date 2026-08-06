package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.data.DeathNoteSavedData;
import cn.blindboxchallenge.item.DeathNoteItem;
import cn.blindboxchallenge.item.LetterItem;
import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.service.LetterService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * P4 第一批真实客户端 GUI 场景。服务端始终经正式物品右键入口打开屏幕；ciTest 客户端只在
 * 实际看到信件只读页、编辑页和死亡笔记页后点击生产控件，生产 C2S 负责所有提交与校验。
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class P4TextCiScenario {
    public static final String LETTER_BODY = "P4 letter";
    private static ActiveScenario active;

    private P4TextCiScenario() {}

    public static int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("已有 P4 文本 GUI 场景运行中"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer());
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_TEXT_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            active = null;
            CiTestProbe.LOGGER.error("Cannot start P4 text GUI scenario", exception);
            source.sendFailure(Component.literal("CI P4 文本 GUI 场景启动失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int verify(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可核验的 P4 文本 GUI 场景"));
            return 0;
        }
        try {
            active.verify();
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_TEXT_CLIENTS=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot verify P4 text GUI scenario", exception);
            source.sendFailure(Component.literal("CI P4 文本 GUI 断言失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int cleanup(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可清理的 P4 文本 GUI 场景"));
            return 0;
        }
        try {
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_TEXT_CLEANUP=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot clean P4 text GUI scenario", exception);
            source.sendFailure(Component.literal("CI P4 文本 GUI 清理失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int verifyReconnect(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可核验重连的 P4 文本 GUI 场景"));
            return 0;
        }
        try {
            active.verifyReconnect();
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P4_TEXT_RECONNECT=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot verify P4 text reconnect state", exception);
            source.sendFailure(Component.literal("CI P4 信件重连断言失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) return;
        try {
            active.tick();
        } catch (Exception exception) {
            active.fail(exception);
        }
    }

    private enum Phase { WAIT_LETTER_EDIT, WAIT_DEATH_NOTE, WAIT_EXECUTION, READY, FAILED }

    private static final class ActiveScenario {
        private final MinecraftServer server;
        private final ServerPlayer alice;
        private final ServerPlayer bob;
        private final int originalSelectedSlot;
        private final ItemStack originalSelectedStack;
        private final Path marker;
        private final Path deathMarker;
        private final UUID bobUuid;
        private final long startedAt;
        private final boolean originalKeepInventory;
        private int persistedLetterSlot = -1;
        private UUID persistedLetterInstance;
        private Phase phase = Phase.WAIT_LETTER_EDIT;
        private long nextActionTick;
        private long deathEntryDueTick = -1L;
        private String failure;

        private ActiveScenario(MinecraftServer server, ServerPlayer alice, ServerPlayer bob, Path marker, Path deathMarker) {
            this.server = server;
            this.alice = alice;
            this.bob = bob;
            this.originalSelectedSlot = alice.getInventory().selected;
            this.originalSelectedStack = alice.getMainHandItem().copy();
            this.marker = marker;
            this.deathMarker = deathMarker;
            this.bobUuid = bob.getUUID();
            this.startedAt = server.overworld().getGameTime();
            this.originalKeepInventory = server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).get();
        }

        private static ActiveScenario create(MinecraftServer server) throws IOException {
            ServerPlayer alice = requiredPlayer(server, "BlindBoxAlice");
            ServerPlayer bob = requiredPlayer(server, "BlindBoxBob");
            Path markerDirectory = markerDirectory();
            Path marker = markerDirectory.resolve("client-1-p4-text-observed.marker");
            Path deathMarker = markerDirectory.resolve("client-2-p4-death-observed.marker");
            if (Files.exists(marker) || Files.exists(deathMarker)) throw new IllegalStateException("P4 客户端 marker 已存在，拒绝复用旧结果");
            ActiveScenario scenario = new ActiveScenario(server, alice, bob, marker, deathMarker);
            scenario.giveLetterForRealClientUse();
            return scenario;
        }

        private void giveLetterForRealClientUse() {
            ItemStack letter = new ItemStack(ModItems.LETTER.get());
            alice.setItemInHand(InteractionHand.MAIN_HAND, letter);
            alice.containerMenu.broadcastChanges();
            // 此后只能由 Alice 真实客户端的 KeyMapping 右键进入 LetterItem#use；服务端不伪造 use/潜行。
            nextActionTick = server.overworld().getGameTime() + 180L;
        }

        private void tick() {
            if (phase == Phase.FAILED || phase == Phase.READY) return;
            long now = server.overworld().getGameTime();
            if (now - startedAt > 500L) throw new IllegalStateException("P4 文本 GUI 场景超时：" + phase);
            switch (phase) {
                case WAIT_LETTER_EDIT -> {
                    ItemStack letter = alice.getMainHandItem();
                    if (letter.is(ModItems.LETTER.get()) && LETTER_BODY.equals(LetterService.body(letter)) && LetterService.revision(letter) == 1) {
                        preserveEditedLetter(letter);
                        openDeathNote();
                        phase = Phase.WAIT_DEATH_NOTE;
                        nextActionTick = now + 100L;
                    } else if (now > nextActionTick) {
                        throw new IllegalStateException("真实信件编辑 C2S 未写入正文或修订");
                    }
                }
                case WAIT_DEATH_NOTE -> {
                    boolean scheduled = DeathNoteSavedData.get(server.overworld()).entries().stream()
                            .anyMatch(entry -> entry.target().equals(bobUuid));
                    if (scheduled) {
                        ItemStack note = alice.getMainHandItem();
                        if (!note.is(ModItems.DEATH_NOTE.get()) || LetterService.revision(note) != 1) {
                            throw new IllegalStateException("死亡笔记真实菜单提交未递增修订");
                        }
                        deathEntryDueTick = DeathNoteSavedData.get(server.overworld()).entries().stream()
                                .filter(entry -> entry.target().equals(bobUuid)).mapToLong(DeathNoteSavedData.Entry::dueTick).min()
                                .orElseThrow();
                        phase = Phase.WAIT_EXECUTION;
                        nextActionTick = deathEntryDueTick + 60L;
                    } else if (now > nextActionTick) {
                        throw new IllegalStateException("真实死亡笔记 GUI 未建立持久排程");
                    }
                }
                case WAIT_EXECUTION -> {
                    boolean remains = DeathNoteSavedData.get(server.overworld()).entries().stream()
                            .anyMatch(entry -> entry.target().equals(bobUuid));
                    if (now >= deathEntryDueTick && !remains && !bob.isAlive()) {
                        // P1 canonical 资产仍须参与本次汇总回归；仅围绕真实死亡窗口临时保留物品。
                        server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(originalKeepInventory, server);
                        phase = Phase.READY;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P4_TEXT_SERVER=success");
                    } else if (now > nextActionTick) {
                        throw new IllegalStateException("死亡笔记到期后未恰当移除排程或未使目标死亡");
                    }
                }
                default -> { }
            }
        }

        private void openDeathNote() {
            ItemStack note = new ItemStack(ModItems.DEATH_NOTE.get());
            alice.setItemInHand(InteractionHand.MAIN_HAND, note);
            alice.containerMenu.broadcastChanges();
            server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
            // 同样只等待真实客户端右键；不由服务端直调 DeathNoteItem#use。
        }

        /** 将已由真实 GUI 写入的信件放到原先为空的普通背包格，供后续真实断线重连后按 NBT 复核。 */
        private void preserveEditedLetter(ItemStack letter) {
            for (int slot = 9; slot < 36; slot++) {
                if (!alice.getInventory().getItem(slot).isEmpty()) continue;
                persistedLetterSlot = slot;
                persistedLetterInstance = LetterService.ensureInstanceId(letter);
                alice.getInventory().setItem(slot, letter.copy());
                alice.containerMenu.broadcastChanges();
                return;
            }
            throw new IllegalStateException("P4 重连验证没有可用的普通背包空格");
        }

        private void verify() throws IOException {
            if (phase != Phase.READY) throw new IllegalStateException("P4 服务端业务尚未完成：" + phase);
            if (!Files.isRegularFile(marker)) throw new IllegalStateException("缺少真实客户端 GUI marker");
            String value = Files.readString(marker, StandardCharsets.UTF_8);
            if (!value.contains("read_only_screen_observed=true") || !value.contains("letter_edit_clicked=true")
                    || !value.contains("death_note_clicked=true") || !value.contains("normal_use_key_injected=true")
                    || !value.contains("sneak_use_key_injected=true") || !value.contains("server_close_observed=true")
                    || !value.contains("observer_uuid=" + alice.getUUID())) {
                throw new IllegalStateException("P4 客户端 marker 不含真实 GUI 观察字段");
            }
            if (!Files.isRegularFile(deathMarker) || !Files.readString(deathMarker, StandardCharsets.UTF_8)
                    .contains("observer_uuid=" + bobUuid)) {
                throw new IllegalStateException("缺少 Bob 真实死亡界面 marker");
            }
        }

        private void verifyReconnect() {
            if (phase != Phase.READY || persistedLetterSlot < 9 || persistedLetterInstance == null) {
                throw new IllegalStateException("P4 信件尚未进入可重连核验状态");
            }
            ItemStack reloaded = alice.getInventory().getItem(persistedLetterSlot);
            if (!reloaded.is(ModItems.LETTER.get()) || !persistedLetterInstance.equals(LetterService.ensureInstanceId(reloaded))
                    || LetterService.revision(reloaded) != 1 || !LETTER_BODY.equals(LetterService.body(reloaded))) {
                throw new IllegalStateException("真实断线重连后信件 NBT 或修订不一致");
            }
        }

        private void cleanup() {
            alice.closeContainer();
            server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(originalKeepInventory, server);
            if (persistedLetterSlot >= 9) alice.getInventory().setItem(persistedLetterSlot, ItemStack.EMPTY);
            alice.getInventory().selected = originalSelectedSlot;
            alice.setItemInHand(InteractionHand.MAIN_HAND, originalSelectedStack.copy());
            alice.containerMenu.broadcastChanges();
        }

        private void fail(Exception exception) {
            if (phase == Phase.FAILED) return;
            failure = exception.getClass().getSimpleName();
            phase = Phase.FAILED;
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_P4_TEXT=failed", exception);
        }

        private static ServerPlayer requiredPlayer(MinecraftServer server, String name) {
            ServerPlayer player = server.getPlayerList().getPlayerByName(name);
            if (player == null) throw new IllegalStateException("P4 场景缺少在线玩家：" + name);
            return player;
        }

        private static Path markerDirectory() {
            String configured = System.getenv("BLINDBOX_CITEST_P4_MARKER_DIR");
            if (configured == null || configured.isBlank()) throw new IllegalStateException("缺少 BLINDBOX_CITEST_P4_MARKER_DIR");
            return Path.of(configured).toAbsolutePath();
        }
    }
}
