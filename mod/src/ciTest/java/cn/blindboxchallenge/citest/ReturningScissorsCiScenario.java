package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.entity.ReturningScissorsEntity;
import cn.blindboxchallenge.item.ReturningScissorsItem;
import cn.blindboxchallenge.registry.ModItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 045 的独立真实服务端场景。
 *
 * <p>两次投掷都经 {@link ReturningScissorsItem#use} 和 {@link ReturningScissorsItem#releaseUsing} 入口：
 * 第一次命中真实猪并让两个真实客户端观察同一投掷、目标、主人和返航态；第二次在 36 格背包均满时
 * 命中真实猪，断言只能在主人位置产生带原始 NBT 的兜底掉落物。探针不属于正式 Jar。</p>
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ReturningScissorsCiScenario {
    private static final int OBSERVATION_TICKS = 100;
    private static final int RESULT_TIMEOUT_TICKS = 220;
    private static final String NORMAL_TOKEN = "normal-hit-return";
    private static final String FULL_TOKEN = "full-inventory-fallback";
    private static ActiveScenario active;

    private ReturningScissorsCiScenario() {
    }

    public static int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("已有 P3 返航剪刀 CI 场景未收尾"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer());
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_SCISSORS_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot start P3 returning scissors CI scenario", exception);
            source.sendFailure(Component.literal("CI P3 返航剪刀场景启动失败：" + exception.getClass().getSimpleName()));
            active = null;
            return 0;
        }
    }

    public static int verifyClientMarkers(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可核验的 P3 返航剪刀 CI 场景"));
            return 0;
        }
        try {
            active.verifyClientMarkers();
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_SCISSORS_CLIENTS=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot verify P3 returning scissors client markers", exception);
            active.cleanup();
            active = null;
            source.sendFailure(Component.literal("CI P3 返航剪刀双端核验失败：" + exception.getClass().getSimpleName()));
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

    private enum Phase {
        OBSERVING_NORMAL_THROW,
        AWAITING_NORMAL_RETURN,
        AWAITING_FULL_FALLBACK,
        RESULT_READY,
        FAILED
    }

    private static final class ActiveScenario {
        private final ServerLevel level;
        private final ServerPlayer alice;
        private final ServerPlayer bob;
        private final InventorySnapshot aliceBefore;
        private final PlayerPosition alicePositionBefore;
        private final PlayerPosition bobPositionBefore;
        private final Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
        private final BlockPos base;
        private final AABB fixtureBounds;
        private ReturningScissorsEntity normalScissors;
        private ReturningScissorsEntity fullScissors;
        private Pig normalTarget;
        private Pig fullTarget;
        private ItemEntity fallbackItem;
        private UUID expectedNormalScissorsId;
        private UUID expectedTargetId;
        private UUID expectedOwnerId;
        private UUID expectedFallbackId;
        private float normalTargetHealth;
        private int phaseTicks;
        private Phase phase = Phase.OBSERVING_NORMAL_THROW;
        private String failure;
        private boolean cleaned;

        private ActiveScenario(ServerLevel level, ServerPlayer alice, ServerPlayer bob, BlockPos base) {
            this.level = level;
            this.alice = alice;
            this.bob = bob;
            this.base = base;
            this.fixtureBounds = new AABB(base.offset(-10, 0, -30), base.offset(10, 8, 16));
            this.aliceBefore = InventorySnapshot.capture(alice.getInventory());
            this.alicePositionBefore = PlayerPosition.capture(alice);
            this.bobPositionBefore = PlayerPosition.capture(bob);
        }

        private static ActiveScenario create(MinecraftServer server) {
            ServerPlayer alice = server.getPlayerList().getPlayerByName("BlindBoxAlice");
            ServerPlayer bob = server.getPlayerList().getPlayerByName("BlindBoxBob");
            if (alice == null || bob == null || alice.serverLevel() != bob.serverLevel()) {
                throw new IllegalStateException("返航剪刀场景要求 Alice 和 Bob 在线且位于同一维度");
            }
            if (alice.getAbilities().instabuild) {
                throw new IllegalStateException("返航剪刀守恒场景必须使用非创造模式的真实生存投掷");
            }
            BlockPos base = new BlockPos(Mth.floor(alice.getX()), 120, Mth.floor(alice.getZ()));
            ActiveScenario scenario = new ActiveScenario(alice.serverLevel(), alice, bob, base);
            try {
                scenario.setup();
                return scenario;
            } catch (Exception exception) {
                scenario.cleanup();
                throw exception;
            }
        }

        private void setup() {
            saveAndPrepareFixture();
            alice.teleportTo(level, base.getX() + 0.5D, base.getY() + 1.0D, base.getZ() - 4.5D, 0.0F, 0.0F);
            bob.teleportTo(level, base.getX() + 4.5D, base.getY() + 1.0D, base.getZ() - 3.0D, 0.0F, 0.0F);
            normalScissors = throwOne(NORMAL_TOKEN);
            expectedNormalScissorsId = minecraftEntity(normalScissors).getUUID();
            expectedOwnerId = alice.getUUID();
            freezeForObservation(normalScissors);
            alice.containerMenu.broadcastChanges();
            bob.containerMenu.broadcastChanges();
        }

        private void saveAndPrepareFixture() {
            for (int x = -10; x <= 10; x++) {
                for (int y = 0; y <= 8; y++) {
                    for (int z = -30; z <= 16; z++) {
                        BlockPos pos = base.offset(x, y, z);
                        originalBlocks.put(pos.immutable(), level.getBlockState(pos));
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            for (int x = -10; x <= 10; x++) {
                for (int z = -30; z <= 16; z++) level.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }

        private ReturningScissorsEntity throwOne(String token) {
            ItemStack source = new ItemStack(ModItems.RETURNING_SCISSORS.get());
            source.getOrCreateTag().putString("ReturningScissorsCiToken", token);
            alice.getInventory().selected = 0;
            alice.setItemInHand(InteractionHand.MAIN_HAND, source);
            InteractionResultHolder<ItemStack> use = source.getItem().use(level, alice, InteractionHand.MAIN_HAND);
            if (!use.getResult().consumesAction() || !alice.isUsingItem()) {
                throw new IllegalStateException("返航剪刀真实 use 入口没有开始蓄力：" + token);
            }
            int before = scissorsInFixture().size();
            source.getItem().releaseUsing(source, level, alice,
                    source.getItem().getUseDuration(source) - ReturningScissorsItem.THROW_THRESHOLD_TICKS);
            alice.stopUsingItem();
            List<ReturningScissorsEntity> after = scissorsInFixture();
            if (!source.isEmpty() || after.size() != before + 1) {
                throw new IllegalStateException("返航剪刀真实 releaseUsing 没有恰好扣除一件并生成一个实体：" + token);
            }
            ReturningScissorsEntity scissors = after.stream()
                    .filter(entity -> expectedToken(entity, token) && minecraftProjectile(entity).getOwner() == alice)
                    .findFirst().orElseThrow(() -> new IllegalStateException("返航剪刀实体缺少完整原始 NBT 或主人：" + token));
            if (scissors.storedStack().getDamageValue() != 1) {
                throw new IllegalStateException("返航剪刀耐久没有写入实体持有的单件副本：" + token);
            }
            return scissors;
        }

        private void freezeForObservation(ReturningScissorsEntity scissors) {
            Entity entity = minecraftEntity(scissors);
            entity.setNoGravity(true);
            entity.setPos(base.getX() + 0.5D, base.getY() + 2.1D, base.getZ());
            entity.setDeltaMovement(Vec3.ZERO);
        }

        private void tick() {
            if (phase == Phase.FAILED || phase == Phase.RESULT_READY) return;
            phaseTicks++;
            if (phase == Phase.OBSERVING_NORMAL_THROW && phaseTicks >= OBSERVATION_TICKS) {
                activateNormalHit();
                phase = Phase.AWAITING_NORMAL_RETURN;
                phaseTicks = 0;
                return;
            }
            if (phase == Phase.AWAITING_NORMAL_RETURN) {
                if (minecraftEntity(normalScissors).isRemoved()) {
                    assertNormalReturn();
                    startFullInventoryFallback();
                    phase = Phase.AWAITING_FULL_FALLBACK;
                    phaseTicks = 0;
                } else if (phaseTicks > RESULT_TIMEOUT_TICKS) {
                    throw new IllegalStateException("返航剪刀真实命中后未在限定 tick 内回到主人");
                }
                return;
            }
            if (phase == Phase.AWAITING_FULL_FALLBACK) {
                if (minecraftEntity(fullScissors).isRemoved()) {
                    assertFullInventoryFallback();
                    phase = Phase.RESULT_READY;
                    CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P3_SCISSORS_SERVER=success scissors={} target={} owner={} fallback={}",
                            expectedNormalScissorsId, expectedTargetId, expectedOwnerId, expectedFallbackId);
                } else if (phaseTicks > RESULT_TIMEOUT_TICKS) {
                    throw new IllegalStateException("返航剪刀满包兜底未在限定 tick 内结算");
                }
            }
        }

        private void activateNormalHit() {
            normalTarget = createTarget(minecraftEntity(normalScissors).position().add(0.0D, -0.5D, 4.0D));
            expectedTargetId = normalTarget.getUUID();
            normalTargetHealth = normalTarget.getHealth();
            // 保持真实投掷入口产生的实体，只在双端观察窗结束后调整其物理位置/速度来稳定命中时序。
            minecraftEntity(normalScissors).setNoGravity(true);
            minecraftEntity(normalScissors).setDeltaMovement(new Vec3(0.0D, 0.0D, 1.0D));
            alice.teleportTo(level, base.getX() + 0.5D, base.getY() + 1.0D, base.getZ() - 20.5D, 0.0F, 0.0F);
            alice.hurtMarked = true;
        }

        private Pig createTarget(Vec3 position) {
            Pig target = EntityType.PIG.create(level);
            if (target == null) throw new IllegalStateException("无法创建返航剪刀真实命中目标");
            target.setNoGravity(true);
            target.setPos(position.x, position.y, position.z);
            if (!level.addFreshEntity(target)) throw new IllegalStateException("返航剪刀目标未加入服务端世界");
            return target;
        }

        private void assertNormalReturn() {
            if (normalTarget.getHealth() >= normalTargetHealth || !normalScissors.hitTargetId().filter(expectedTargetId::equals).isPresent()
                    || !hasTaggedInventoryStack(NORMAL_TOKEN) || countTaggedInventoryStacks(NORMAL_TOKEN) != 1) {
                throw new IllegalStateException("返航剪刀命中、目标同步、完整 NBT 回收或物品守恒断言失败");
            }
        }

        private void startFullInventoryFallback() {
            if (normalTarget != null && !normalTarget.isRemoved()) normalTarget.discard();
            fillMainInventory();
            alice.teleportTo(level, base.getX() + 0.5D, base.getY() + 1.0D, base.getZ() - 4.5D, 0.0F, 0.0F);
            fullScissors = throwOne(FULL_TOKEN);
            // releaseUsing 后的第 0 格会空出；立刻填满它，确保回收只能走主人位置掉落兜底而不能进背包。
            alice.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            freezeForObservation(fullScissors);
            fullTarget = createTarget(minecraftEntity(fullScissors).position().add(0.0D, -0.5D, 4.0D));
            minecraftEntity(fullScissors).setNoGravity(true);
            minecraftEntity(fullScissors).setDeltaMovement(new Vec3(0.0D, 0.0D, 1.0D));
            alice.teleportTo(level, base.getX() + 0.5D, base.getY() + 1.0D, base.getZ() - 12.5D, 0.0F, 0.0F);
            alice.containerMenu.broadcastChanges();
        }

        private void fillMainInventory() {
            for (int slot = 0; slot < 36; slot++) alice.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }

        private void assertFullInventoryFallback() {
            List<ItemEntity> fallbacks = level.getEntitiesOfClass(ItemEntity.class, fixtureBounds.inflate(8.0D),
                    item -> expectedToken(item.getItem(), FULL_TOKEN));
            if (fallbacks.size() != 1 || countTaggedInventoryStacks(FULL_TOKEN) != 0) {
                throw new IllegalStateException("返航剪刀满包时没有只在世界掉落完整物品栈");
            }
            fallbackItem = fallbacks.get(0);
            expectedFallbackId = fallbackItem.getUUID();
            if (fallbackItem.getItem().getCount() != 1 || fallbackItem.getItem().getDamageValue() != 1
                    || !expectedToken(fallbackItem.getItem(), FULL_TOKEN)
                    || fallbackItem.distanceToSqr(alice) > 9.0D) {
                throw new IllegalStateException("返航剪刀满包兜底掉落的位置、耐久或完整 NBT 不正确");
            }
        }

        private void verifyClientMarkers() throws IOException {
            if (phase == Phase.FAILED) throw new IllegalStateException("返航剪刀服务端场景已失败：" + failure);
            if (phase != Phase.RESULT_READY) throw new IllegalStateException("返航剪刀服务端尚未完成命中、返航与满包断言");
            Path directory = markerDirectory();
            verifyMarker(readMarker(directory.resolve("client-1-scissors-observed.marker")), "客户端一");
            verifyMarker(readMarker(directory.resolve("client-2-scissors-observed.marker")), "客户端二");
        }

        private void verifyMarker(Map<String, String> marker, String client) {
            if (!"1".equals(marker.get("schema")) || !"true".equals(marker.get("returning"))) {
                throw new IllegalStateException(client + " 未由真实返航态写入剪刀 marker");
            }
            assertUuid(marker, "scissors", expectedNormalScissorsId, client);
            assertUuid(marker, "target", expectedTargetId, client);
            assertUuid(marker, "owner", expectedOwnerId, client);
        }

        private static void assertUuid(Map<String, String> marker, String key, UUID expected, String client) {
            if (!expected.toString().equals(marker.get(key))) {
                throw new IllegalStateException(client + " 未观察到同一 " + key + " UUID；期望=" + expected + "，实际=" + marker.get(key));
            }
        }

        private List<ReturningScissorsEntity> scissorsInFixture() {
            return level.getEntitiesOfClass(ReturningScissorsEntity.class, fixtureBounds.inflate(8.0D));
        }

        private boolean hasTaggedInventoryStack(String token) {
            return countTaggedInventoryStacks(token) > 0;
        }

        private int countTaggedInventoryStacks(String token) {
            int count = 0;
            for (int slot = 0; slot < alice.getInventory().getContainerSize(); slot++) {
                ItemStack stack = alice.getInventory().getItem(slot);
                if (expectedToken(stack, token)) count += stack.getCount();
            }
            return count;
        }

        private static boolean expectedToken(ReturningScissorsEntity entity, String token) {
            return expectedToken(entity.storedStack(), token);
        }

        private static boolean expectedToken(ItemStack stack, String token) {
            return stack.is(ModItems.RETURNING_SCISSORS.get()) && stack.hasTag()
                    && token.equals(stack.getTag().getString("ReturningScissorsCiToken"));
        }

        private static Path markerDirectory() {
            String configured = System.getenv("BLINDBOX_CITEST_SCISSORS_MARKER_DIR");
            if (configured == null || configured.isBlank()) throw new IllegalStateException("缺少 BLINDBOX_CITEST_SCISSORS_MARKER_DIR");
            Path directory = Path.of(configured).toAbsolutePath().normalize();
            if (!Files.isDirectory(directory)) throw new IllegalStateException("返航剪刀 marker 目录不存在：" + directory);
            return directory;
        }

        private static Map<String, String> readMarker(Path marker) throws IOException {
            if (!Files.isRegularFile(marker)) throw new IllegalStateException("缺少客户端真实返航剪刀 marker：" + marker);
            Map<String, String> fields = new HashMap<>();
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator <= 0 || separator != line.lastIndexOf('=')) throw new IllegalStateException("返航剪刀 marker 格式非法：" + marker);
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (key.isBlank() || value.isBlank() || fields.putIfAbsent(key, value) != null) {
                    throw new IllegalStateException("返航剪刀 marker 存在空字段或重复字段：" + marker);
                }
            }
            if (fields.size() != 5) throw new IllegalStateException("返航剪刀 marker 字段数量不正确：" + marker);
            return fields;
        }

        private void fail(Exception exception) {
            failure = exception.getClass().getSimpleName() + ": " + Objects.toString(exception.getMessage(), "");
            phase = Phase.FAILED;
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_P3_SCISSORS_SERVER=failed {}", failure, exception);
        }

        private void cleanup() {
            if (cleaned) return;
            cleaned = true;
            discard(normalScissors);
            discard(fullScissors);
            discard(normalTarget);
            discard(fullTarget);
            discard(fallbackItem);
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds.inflate(8.0D),
                    entity -> expectedToken(entity.getItem(), NORMAL_TOKEN) || expectedToken(entity.getItem(), FULL_TOKEN))) item.discard();
            originalBlocks.forEach((pos, state) -> level.setBlock(pos, state, 3));
            aliceBefore.restore(alice.getInventory());
            alicePositionBefore.restore(alice, level);
            bobPositionBefore.restore(bob, level);
            alice.stopUsingItem();
            alice.containerMenu.broadcastChanges();
            bob.containerMenu.broadcastChanges();
        }

        private static void discard(Entity entity) {
            if (entity != null && !minecraftEntity(entity).isRemoved()) minecraftEntity(entity).discard();
        }
    }

    /** ciTest 单独重混淆时，继承的 Minecraft 方法须以 Minecraft 基类调用。 */
    private static Entity minecraftEntity(Entity entity) {
        return entity;
    }

    private static net.minecraft.world.entity.projectile.Projectile minecraftProjectile(
            net.minecraft.world.entity.projectile.Projectile projectile) {
        return projectile;
    }

    private record InventorySnapshot(List<ItemStack> slots, int selected) {
        private static InventorySnapshot capture(Inventory inventory) {
            List<ItemStack> slots = new ArrayList<>();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) slots.add(inventory.getItem(slot).copy());
            return new InventorySnapshot(slots, inventory.selected);
        }

        private void restore(Inventory inventory) {
            for (int slot = 0; slot < slots.size(); slot++) inventory.setItem(slot, slots.get(slot).copy());
            inventory.selected = selected;
        }
    }

    private record PlayerPosition(double x, double y, double z, float yRot, float xRot) {
        private static PlayerPosition capture(ServerPlayer player) {
            return new PlayerPosition(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }

        private void restore(ServerPlayer player, ServerLevel level) {
            player.teleportTo(level, x, y, z, yRot, xRot);
        }
    }
}
