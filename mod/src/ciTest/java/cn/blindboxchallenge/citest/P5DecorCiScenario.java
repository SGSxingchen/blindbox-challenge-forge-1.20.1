package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.registry.ModBlocks;
import cn.blindboxchallenge.registry.ModItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

/**
 * P5 三项中性装饰方块的真实双客户端放置与回收场景。
 *
 * <p>服务端仅搭建临时石质支撑、发放正式 {@link net.minecraft.world.item.BlockItem}，再核验真实
 * C2S 放置产生的方块状态、物品扣除及原版破坏掉落实体。它绝不向三个目标格 setBlock、不直调
 * BlockItem#useOn，也不调用 removeBlock；客户端必须经原版 KeyMapping 右键/攻击路径完成操作。
 * 两份 marker 只记录各自客户端已经实际同步到的方块状态和掉落实体 UUID，服务端随后逐项反查。</p>
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class P5DecorCiScenario {
    private static final int MAX_PHASE_TICKS = 320;
    private static final int DROP_OBSERVATION_TICKS = 40;
    private static ActiveScenario active;

    public static final List<DecorRound> ROUNDS = List.of(
            new DecorRound(1, "BlindBoxAlice", ModItems.ABSTRACT_WHITE_FIGURINE, ModBlocks.ABSTRACT_WHITE_FIGURINE),
            new DecorRound(2, "BlindBoxBob", ModItems.FLOOR_ART_PANEL, ModBlocks.FLOOR_ART_PANEL),
            new DecorRound(3, "BlindBoxAlice", ModItems.NEUTRAL_TROPHY, ModBlocks.NEUTRAL_TROPHY));

    private P5DecorCiScenario() {
    }

    public static int start(CommandSourceStack source) {
        return start(source, false);
    }

    /** 单客户端专项绝不复用双端结果：同一生产路径由唯一真实客户端独立完成三轮。 */
    public static int startSingle(CommandSourceStack source) {
        return start(source, true);
    }

    private static int start(CommandSourceStack source, boolean singleClient) {
        if (active != null) {
            source.sendFailure(Component.literal("已有 P5 装饰方块客户端场景未清理"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer(), singleClient);
            source.sendSuccess(() -> Component.literal(singleClient
                    ? "BLINDBOX_CITEST_P5_DECOR_SINGLE_STARTED=success"
                    : "BLINDBOX_CITEST_P5_DECOR_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            active = null;
            CiTestProbe.LOGGER.error("Cannot start P5 decorative block client scenario", exception);
            source.sendFailure(Component.literal("CI P5 装饰方块场景启动失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int verify(CommandSourceStack source) {
        return verify(source, false);
    }

    public static int verifySingle(CommandSourceStack source) {
        return verify(source, true);
    }

    private static int verify(CommandSourceStack source, boolean singleClient) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可核验的 P5 装饰方块客户端场景"));
            return 0;
        }
        try {
            if (active.singleClient != singleClient) throw new IllegalStateException("P5 单/双客户端核验命令与运行场景不匹配");
            active.verifyClientMarkers();
            source.sendSuccess(() -> Component.literal(singleClient
                    ? "BLINDBOX_CITEST_P5_DECOR_SINGLE_CLIENT=success"
                    : "BLINDBOX_CITEST_P5_DECOR_CLIENTS=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot verify P5 decorative block client markers", exception);
            source.sendFailure(Component.literal("CI P5 装饰方块双端断言失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int cleanup(CommandSourceStack source) {
        return cleanup(source, false);
    }

    public static int cleanupSingle(CommandSourceStack source) {
        return cleanup(source, true);
    }

    private static int cleanup(CommandSourceStack source, boolean singleClient) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可清理的 P5 装饰方块客户端场景"));
            return 0;
        }
        try {
            if (active.singleClient != singleClient) throw new IllegalStateException("P5 单/双客户端清理命令与运行场景不匹配");
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal(singleClient
                    ? "BLINDBOX_CITEST_P5_DECOR_SINGLE_CLEANUP=success"
                    : "BLINDBOX_CITEST_P5_DECOR_CLEANUP=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot clean P5 decorative block client scenario", exception);
            source.sendFailure(Component.literal("CI P5 装饰方块清理失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) return;
        try {
            active.tick();
        } catch (Exception exception) {
            active.fail(exception);
        }
    }

    /** 供 ciTest 客户端从本地真实世界状态推导目标格；不包含服务端 UUID 或成功结论。 */
    public static BlockPos target(Level level, int roundIndex) {
        return fixtureBase(level).offset((roundIndex - 1) * 8, 1, 0);
    }

    /** 供 ciTest 客户端确认自己已同步到服务器定位后，才操作原版输入映射。 */
    public static Vec3 placementStance(Level level, int roundIndex) {
        BlockPos target = target(level, roundIndex);
        return new Vec3(target.getX() + 0.5D, target.getY(), target.getZ() + 3.5D);
    }

    /** 破坏站位保留掉落实体与玩家之间的距离，避免即时拾取掩盖正常战利品路径。 */
    public static Vec3 breakingStance(Level level, int roundIndex) {
        BlockPos target = target(level, roundIndex);
        // 4.35 格对 2/16 格高的地面画板会使眼睛到命中面的真实距离超过生存 4.5 格；3.75 格
        // 仍避免掉落即时碰撞，又让三个模型高度都在原版破坏距离内。
        return new Vec3(target.getX() + 0.5D, target.getY(), target.getZ() + 3.75D);
    }

    private static BlockPos fixtureBase(Level level) {
        BlockPos spawn = level.getSharedSpawnPos();
        // 高于自然生成而又低于 14 格高摆件模型的上界；全部 X/Z 仍在出生区附近，客户端不强加载未知区块。
        return new BlockPos(spawn.getX() + 64, level.getMaxBuildHeight() - 32, spawn.getZ() + 64);
    }

    public record DecorRound(int index, String actorName, RegistryObject<Item> item, RegistryObject<Block> block) {
        public String itemId() {
            return String.valueOf(item.getId());
        }

        public String blockId() {
            return String.valueOf(block.getId());
        }
    }

    private enum Phase {
        WAIT_FOR_INITIAL_PICKUP,
        WAIT_FOR_PLACE,
        WAIT_FOR_BREAK,
        OBSERVE_DROP,
        WAIT_FOR_RECOVERY_PICKUP,
        READY,
        FAILED
    }

    private static final class ActiveScenario {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final boolean singleClient;
        private final ServerPlayer alice;
        private final ServerPlayer bob;
        private final PlayerSnapshot aliceBefore;
        private final PlayerSnapshot bobBefore;
        private final Map<BlockPos, BlockState> supportBefore;
        private final AABB fixtureBounds;
        private final UUID aliceUuid;
        private final UUID bobUuid;
        private final Map<Integer, UUID> initialDrops = new HashMap<>();
        private final Map<Integer, UUID> drops = new HashMap<>();
        private int roundOffset;
        private int phaseTicks;
        private Phase phase = Phase.WAIT_FOR_PLACE;
        private String failure;

        private ActiveScenario(MinecraftServer server, ServerLevel level, boolean singleClient, ServerPlayer alice, ServerPlayer bob,
                               Map<BlockPos, BlockState> supportBefore, AABB fixtureBounds) {
            this.server = server;
            this.level = level;
            this.singleClient = singleClient;
            this.alice = alice;
            this.bob = bob;
            this.aliceBefore = PlayerSnapshot.capture(alice);
            this.bobBefore = PlayerSnapshot.capture(bob);
            this.supportBefore = supportBefore;
            this.fixtureBounds = fixtureBounds;
            this.aliceUuid = alice.getUUID();
            this.bobUuid = bob.getUUID();
        }

        private static ActiveScenario create(MinecraftServer server, boolean singleClient) throws IOException {
            Path markerDirectory = markerDirectory();
            ensureMarkersAbsent(markerDirectory, singleClient);
            ServerPlayer alice = requiredPlayer(server, "BlindBoxAlice");
            ServerPlayer bob = singleClient ? alice : requiredPlayer(server, "BlindBoxBob");
            assertNoDecorItems(alice);
            assertNoDecorItems(bob);
            if (alice.getAbilities().instabuild || bob.getAbilities().instabuild) {
                throw new IllegalStateException("P5 装饰方块场景拒绝创造模式玩家");
            }
            if (!server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS).get()) {
                throw new IllegalStateException("P5 装饰方块场景要求 doTileDrops=true");
            }
            ServerLevel level = server.overworld();
            Map<BlockPos, BlockState> supportBefore = captureAndValidateFixtureAir(level);
            AABB bounds = fixtureBounds(level);
            ActiveScenario scenario = new ActiveScenario(server, level, singleClient, alice, bob, supportBefore, bounds);
            try {
                scenario.placeSupports();
                scenario.armRound();
                return scenario;
            } catch (Exception exception) {
                scenario.cleanup();
                throw exception;
            }
        }

        private void tick() {
            if (phase == Phase.READY || phase == Phase.FAILED) return;
            if (++phaseTicks > MAX_PHASE_TICKS) {
                throw new IllegalStateException("P5 装饰方块真实客户端场景超时：" + phase + "，轮次=" + round().index()
                        + "，client_diagnostics=" + clientDiagnostics(singleClient));
            }
            DecorRound round = round();
            BlockPos target = target(level, round.index());
            ServerPlayer actor = actor(round);
            switch (phase) {
                case WAIT_FOR_INITIAL_PICKUP -> {
                    UUID initialDrop = initialDrops.get(round.index());
                    if (initialDrop == null) throw new IllegalStateException("P5 缺少初始真实拾取夹具账本：轮次=" + round.index());
                    if (countInventory(actor, round.item().get()) != 1
                            || level.getEntitiesOfClass(ItemEntity.class, fixtureBounds, entity -> initialDrop.equals(entity.getUUID())).size() != 0) {
                        return;
                    }
                    selectOnlyFixtureItem(actor, round.item().get());
                    actor.containerMenu.broadcastChanges();
                    phase = Phase.WAIT_FOR_PLACE;
                    phaseTicks = 0;
                    CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P5_DECOR_ROUND_{}_PLACE_READY=success target={} item={} actor={} initial_drop={}",
                            round.index(), position(target), round.itemId(), round.actorName(), initialDrop);
                }
                case WAIT_FOR_PLACE -> {
                    BlockState state = level.getBlockState(target);
                    if (!state.is(round.block().get())) return;
                    if (!actor.getMainHandItem().isEmpty()) {
                        throw new IllegalStateException("真实客户端放置后正式 BlockItem 未恰好扣除：轮次=" + round.index());
                    }
                    prepareBreak(round);
                    phase = Phase.WAIT_FOR_BREAK;
                    phaseTicks = 0;
                    CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P5_DECOR_ROUND_{}_BREAK_READY=success target={} block={}",
                            round.index(), position(target), round.blockId());
                }
                case WAIT_FOR_BREAK -> {
                    if (!level.getBlockState(target).isAir()) return;
                    List<ItemEntity> matching = level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.75D),
                            entity -> entity.isAlive() && entity.getItem().is(round.item().get()) && entity.getItem().getCount() == 1);
                    if (matching.size() != 1) {
                        throw new IllegalStateException("真实破坏后没有恰好一个同名正常掉落实体：轮次=" + round.index()
                                + "，实际=" + matching.size());
                    }
                    drops.put(round.index(), matching.get(0).getUUID());
                    phase = Phase.OBSERVE_DROP;
                    phaseTicks = 0;
                    CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P5_DECOR_ROUND_{}_SERVER_DROP=success target={} drop={} item={}",
                            round.index(), position(target), matching.get(0).getUUID(), round.itemId());
                }
                case OBSERVE_DROP -> {
                    UUID drop = drops.get(round.index());
                    ItemEntity entity = findExpectedDrop(round, target, drop);
                    if (entity == null) {
                        throw new IllegalStateException("客户端观察窗口内正常掉落实体消失：轮次=" + round.index());
                    }
                    if (phaseTicks < DROP_OBSERVATION_TICKS) return;
                    // 两个真实客户端已有完整观察窗口后，仍必须保留同一枚生产掉落实体并让玩家通过
                    // 原版碰撞拾取回收；不得删除后直接补物，也不得把 marker 当作回收结论。
                    if (roundOffset + 1 == ROUNDS.size()) {
                        moveFixturePlayer(actor, new Vec3(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D));
                        phase = Phase.WAIT_FOR_RECOVERY_PICKUP;
                        phaseTicks = 0;
                        return;
                    }
                    moveFixturePlayer(actor, new Vec3(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D));
                    phase = Phase.WAIT_FOR_RECOVERY_PICKUP;
                    phaseTicks = 0;
                }
                case WAIT_FOR_RECOVERY_PICKUP -> {
                    UUID drop = drops.get(round.index());
                    if (countInventory(actor, round.item().get()) != 1 || drop == null
                            || !level.getEntitiesOfClass(ItemEntity.class, fixtureBounds, entity -> drop.equals(entity.getUUID())).isEmpty()) return;
                    if (roundOffset + 1 == ROUNDS.size()) {
                        phase = Phase.READY;
                        CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P5_DECOR_SERVER=success rounds={}", ROUNDS.size());
                        return;
                    }
                    roundOffset++;
                    armRound();
                }
                default -> { }
            }
        }

        private void armRound() {
            DecorRound round = round();
            BlockPos target = target(level, round.index());
            if (!level.getBlockState(target).isAir()) {
                throw new IllegalStateException("P5 目标格不是空气，拒绝覆盖：轮次=" + round.index() + "，位置=" + position(target));
            }
            if (!level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.75D), ItemEntity::isAlive).isEmpty()) {
                throw new IllegalStateException("P5 目标格附近已有未知掉落实体，拒绝覆盖：轮次=" + round.index());
            }
            ServerPlayer actor = actor(round);
            ServerPlayer observer = observer(round);
            moveFixturePlayer(actor, placementStance(level, round.index()));
            // 单客户端专项由同一真实玩家操作并观察，不能把刚定位到放置位的 actor 又覆盖到观察位。
            if (observer != actor) moveFixturePlayer(observer, observerStance(level, round.index()));
            // 初始材料同样不直接写入玩家手中：只生成临时 ItemEntity，再由站在其上的真实生存玩家
            // 走原版碰撞拾取路径取得。后续仍严格要求放置后为 0、正常掉落后重新回收为 1。
            ItemEntity initial = new ItemEntity(level, actor.getX(), actor.getY(), actor.getZ(), new ItemStack(round.item().get(), 1));
            initial.setPickUpDelay(0);
            if (!level.addFreshEntity(initial)) throw new IllegalStateException("P5 初始正式 BlockItem 掉落实体未加入世界");
            initialDrops.put(round.index(), initial.getUUID());
            phase = Phase.WAIT_FOR_INITIAL_PICKUP;
            phaseTicks = 0;
        }

        private void prepareBreak(DecorRound round) {
            ServerPlayer actor = actor(round);
            moveFixturePlayer(actor, breakingStance(level, round.index()));
            actor.containerMenu.broadcastChanges();
        }

        private void verifyClientMarkers() throws IOException {
            if (phase == Phase.FAILED) throw new IllegalStateException("P5 装饰方块服务端场景已失败：" + failure);
            if (phase != Phase.READY) throw new IllegalStateException("P5 装饰方块服务端场景尚未完成真实放置/回收：" + phase);
            if (singleClient) {
                verifyMarker(readMarker(markerDirectory().resolve("client-1-p5-decor-single-observed.marker")), aliceUuid, "单客户端");
            } else {
                verifyMarker(readMarker(markerDirectory().resolve("client-1-p5-decor-observed.marker")), aliceUuid, "客户端一");
                verifyMarker(readMarker(markerDirectory().resolve("client-2-p5-decor-observed.marker")), bobUuid, "客户端二");
            }
        }

        private void verifyMarker(Map<String, String> marker, UUID expectedObserver, String clientName) {
            if (!"1".equals(marker.get("schema")) || !expectedObserver.toString().equals(marker.get("observer_uuid"))) {
                throw new IllegalStateException(clientName + " P5 marker 未由对应真实客户端写入");
            }
            for (DecorRound round : ROUNDS) {
                String prefix = "round" + round.index() + "_";
                BlockPos target = target(level, round.index());
                UUID expectedDrop = drops.get(round.index());
                if (expectedDrop == null) throw new IllegalStateException("服务端缺少 P5 正常掉落实体账本：轮次=" + round.index());
                if (!position(target).equals(marker.get(prefix + "block")) || !round.blockId().equals(marker.get(prefix + "state"))
                        || !expectedDrop.toString().equals(marker.get(prefix + "drop")) || !round.itemId().equals(marker.get(prefix + "item"))) {
                    throw new IllegalStateException(clientName + " 未观察到同一轮次的生产方块状态和正常掉落实体：轮次=" + round.index());
                }
            }
            if (marker.size() != 14) throw new IllegalStateException(clientName + " P5 marker 字段数量不正确：" + marker.size());
        }

        private void placeSupports() {
            for (BlockPos support : supportPositions(level)) {
                level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
            }
        }

        private ItemEntity findExpectedDrop(DecorRound round, BlockPos target, UUID expected) {
            List<ItemEntity> matching = level.getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(1.75D),
                    entity -> entity.isAlive() && expected.equals(entity.getUUID())
                            && entity.getItem().is(round.item().get()) && entity.getItem().getCount() == 1);
            return matching.size() == 1 ? matching.get(0) : null;
        }

        private DecorRound round() {
            return ROUNDS.get(roundOffset);
        }

        private ServerPlayer actor(DecorRound round) {
            return singleClient || "BlindBoxAlice".equals(round.actorName()) ? alice : bob;
        }

        private ServerPlayer observer(DecorRound round) {
            return singleClient ? alice : ("BlindBoxAlice".equals(round.actorName()) ? bob : alice);
        }

        private void cleanup() {
            for (UUID initialDrop : initialDrops.values()) {
                level.getEntitiesOfClass(ItemEntity.class, fixtureBounds, entity -> initialDrop.equals(entity.getUUID())).forEach(ItemEntity::discard);
            }
            for (UUID drop : drops.values()) {
                level.getEntitiesOfClass(ItemEntity.class, fixtureBounds, entity -> drop.equals(entity.getUUID())).forEach(ItemEntity::discard);
            }
            removeDecorItems(alice);
            removeDecorItems(bob);
            aliceBefore.restore(alice);
            bobBefore.restore(bob);
            supportBefore.forEach((position, state) -> level.setBlock(position, state, 3));
        }

        private void fail(Exception exception) {
            if (phase == Phase.FAILED) return;
            failure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            phase = Phase.FAILED;
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_P5_DECOR=failed {}", failure, exception);
        }
    }

    private record PlayerSnapshot(ServerLevel level, double x, double y, double z, float yRot, float xRot,
                                  int selected, ItemStack selectedStack) {
        private static PlayerSnapshot capture(ServerPlayer player) {
            int selected = player.getInventory().selected;
            return new PlayerSnapshot(player.serverLevel(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                    selected, player.getInventory().getItem(selected).copy());
        }

        private void restore(ServerPlayer player) {
            player.teleportTo(level, x, y, z, yRot, xRot);
            player.setDeltaMovement(Vec3.ZERO);
            player.hurtMarked = true;
            player.resetFallDistance();
            player.getInventory().selected = selected;
            player.getInventory().setItem(selected, selectedStack.copy());
            player.connection.send(new ClientboundSetCarriedItemPacket(selected));
            player.containerMenu.broadcastChanges();
        }
    }

    private static void moveFixturePlayer(ServerPlayer player, Vec3 destination) {
        player.stopRiding();
        player.teleportTo(player.serverLevel(), destination.x, destination.y, destination.z, 0.0F, 0.0F);
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
        player.resetFallDistance();
    }

    private static void assertNoDecorItems(ServerPlayer player) {
        for (DecorRound round : ROUNDS) {
            if (countInventory(player, round.item().get()) != 0) {
                throw new IllegalStateException("P5 场景拒绝覆盖玩家既有装饰方块物品：" + player.getGameProfile().getName());
            }
        }
    }

    private static int countInventory(ServerPlayer player, Item expected) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(expected)) count += stack.getCount();
        }
        return count;
    }

    private static void selectOnlyFixtureItem(ServerPlayer player, Item expected) {
        int found = -1;
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (!stack.is(expected)) continue;
            if (stack.getCount() != 1 || found >= 0) {
                throw new IllegalStateException("P5 初始真实拾取后物品账本不唯一：" + player.getGameProfile().getName());
            }
            found = slot;
        }
        if (found < 0 || found > 8) {
            throw new IllegalStateException("P5 初始真实拾取未落入可选热键栏：" + player.getGameProfile().getName());
        }
        player.getInventory().selected = found;
        // selected 仅是服务端 Inventory 字段不会必然把热键栏切换同步给真实客户端；显式使用
        // 原版 S2C 已持有槽包，使随后 KeyMapping 仍从客户端当前主手走正常 BlockItem C2S 路径。
        player.connection.send(new ClientboundSetCarriedItemPacket(found));
    }

    private static void removeDecorItems(ServerPlayer player) {
        for (DecorRound round : ROUNDS) {
            for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
                ItemStack stack = player.getInventory().items.get(slot);
                if (stack.is(round.item().get())) player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
        player.containerMenu.broadcastChanges();
    }

    private static Vec3 observerStance(Level level, int roundIndex) {
        BlockPos target = target(level, roundIndex);
        return new Vec3(target.getX() + 0.5D, target.getY(), target.getZ() - 3.5D);
    }

    private static Map<BlockPos, BlockState> captureAndValidateFixtureAir(ServerLevel level) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (BlockPos support : supportPositions(level)) {
            BlockState state = level.getBlockState(support);
            if (!state.isAir()) throw new IllegalStateException("P5 临时支撑位置不是空气，拒绝覆盖：" + position(support));
            result.put(support, state);
        }
        for (DecorRound round : ROUNDS) {
            BlockPos target = target(level, round.index());
            for (int height = 0; height <= 14; height++) {
                BlockPos empty = target.above(height);
                if (!level.getBlockState(empty).isAir()) {
                    throw new IllegalStateException("P5 目标或模型净空不是空气，拒绝覆盖：" + position(empty));
                }
            }
        }
        return result;
    }

    private static List<BlockPos> supportPositions(Level level) {
        BlockPos base = fixtureBase(level);
        List<BlockPos> positions = new ArrayList<>();
        for (int x = -2; x <= 18; x++) {
            for (int z = -5; z <= 5; z++) positions.add(base.offset(x, 0, z));
        }
        return positions;
    }

    private static AABB fixtureBounds(Level level) {
        BlockPos base = fixtureBase(level);
        return new AABB(base.offset(-3, -1, -6), base.offset(20, 18, 6));
    }

    private static ServerPlayer requiredPlayer(MinecraftServer server, String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player == null) throw new IllegalStateException("P5 场景缺少在线玩家：" + name);
        return player;
    }

    private static Path markerDirectory() throws IOException {
        String configured = System.getenv("BLINDBOX_CITEST_P5_MARKER_DIR");
        if (configured == null || configured.isBlank()) throw new IllegalStateException("缺少 BLINDBOX_CITEST_P5_MARKER_DIR");
        Path directory = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) throw new IllegalStateException("P5 marker 目录不存在：" + directory);
        return directory;
    }

    private static void ensureMarkersAbsent(Path directory, boolean singleClient) throws IOException {
        List<String> names = singleClient
                ? List.of("client-1-p5-decor-single-observed.marker", "client-1-p5-decor-single-diagnostic.marker")
                : List.of("client-1-p5-decor-observed.marker", "client-2-p5-decor-observed.marker",
                        "client-1-p5-decor-diagnostic.marker", "client-2-p5-decor-diagnostic.marker");
        for (String name : names) {
            if (Files.exists(directory.resolve(name))) throw new IllegalStateException("P5 客户端 marker 已存在，拒绝复用旧结果：" + name);
        }
    }

    private static String clientDiagnostics(boolean singleClient) {
        List<String> names = singleClient
                ? List.of("client-1-p5-decor-single-diagnostic.marker")
                : List.of("client-1-p5-decor-diagnostic.marker", "client-2-p5-decor-diagnostic.marker");
        try {
            Path directory = markerDirectory();
            List<String> values = new ArrayList<>();
            for (String name : names) {
                Path marker = directory.resolve(name);
                if (!Files.isRegularFile(marker)) {
                    values.add(name + "=missing");
                    continue;
                }
                String content = Files.readString(marker, StandardCharsets.UTF_8)
                        .replace('\r', ' ').replace('\n', ';');
                values.add(name + "=" + content);
            }
            return String.join("|", values);
        } catch (Exception exception) {
            return "unavailable:" + exception.getClass().getSimpleName();
        }
    }

    private static Map<String, String> readMarker(Path marker) throws IOException {
        if (!Files.isRegularFile(marker)) throw new IllegalStateException("缺少 P5 真实客户端观察 marker：" + marker);
        Map<String, String> fields = new HashMap<>();
        for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || separator != line.lastIndexOf('=')) {
                throw new IllegalStateException("P5 客户端 marker 格式非法：" + marker);
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (key.isBlank() || value.isBlank() || fields.putIfAbsent(key, value) != null) {
                throw new IllegalStateException("P5 客户端 marker 有空字段或重复字段：" + marker);
            }
        }
        return fields;
    }

    public static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }
}
