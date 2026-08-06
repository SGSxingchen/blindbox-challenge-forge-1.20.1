package cn.blindboxchallenge.citest;

import cn.blindboxchallenge.block.PillowBlock;
import cn.blindboxchallenge.entity.PillowProjectileEntity;
import cn.blindboxchallenge.entity.PillowSeatEntity;
import cn.blindboxchallenge.entity.PillowVariant;
import cn.blindboxchallenge.item.PillowBlockItem;
import cn.blindboxchallenge.registry.ModBlocks;
import cn.blindboxchallenge.registry.ModItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 008、016 的独立 CI 场景。
 *
 * <p>场景必须经真实 {@link net.minecraft.world.item.BlockItem#useOn(UseOnContext)}、
 * {@link PillowBlockItem#use} 和 {@link PillowBlockItem#releaseUsing} 入口创建生产对象。
 * 客户端标志只会在实际收到同一座位、两个投掷实体、命中同步状态及回收落物后写入；
 * 服务端随后解析两个标志并逐个比对 UUID，不能以文件存在替代双端观察。</p>
 */
@Mod.EventBusSubscriber(modid = CiTestProbe.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PillowCiScenario {
    private static final int OBSERVATION_TICKS = 100;
    private static final int RESULT_TIMEOUT_TICKS = 160;
    private static ActiveScenario active;

    private PillowCiScenario() {
    }

    public static int start(CommandSourceStack source) {
        if (active != null) {
            source.sendFailure(Component.literal("已有 P3 抱枕 CI 场景未收尾"));
            return 0;
        }
        try {
            active = ActiveScenario.create(source.getServer());
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_PILLOW_STARTED=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot start P3 pillow CI scenario", exception);
            source.sendFailure(Component.literal("CI P3 抱枕场景启动失败：" + exception.getClass().getSimpleName()));
            return 0;
        }
    }

    public static int verifyClientMarkers(CommandSourceStack source) {
        if (active == null) {
            source.sendFailure(Component.literal("没有可核验的 P3 抱枕 CI 场景"));
            return 0;
        }
        try {
            active.verifyClientMarkers();
            active.cleanup();
            active = null;
            source.sendSuccess(() -> Component.literal("BLINDBOX_CITEST_P3_PILLOW_CLIENTS=success"), false);
            return 1;
        } catch (Exception exception) {
            CiTestProbe.LOGGER.error("Cannot verify P3 pillow client markers", exception);
            active.cleanup();
            active = null;
            source.sendFailure(Component.literal("CI P3 抱枕双端核验失败：" + exception.getClass().getSimpleName()));
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
        OBSERVING,
        AWAITING_RESULT,
        RESULT_READY,
        FAILED
    }

    private static final class ActiveScenario {
        private final ServerLevel level;
        private final ServerPlayer alice;
        private final ServerPlayer bob;
        private final PlayerSnapshot aliceBefore;
        private final PlayerSnapshot bobBefore;
        private final Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
        private final BlockPos base;
        private final AABB fixtureBounds;
        private final BlockPos stonePillowPos;
        private final BlockPos diamondPillowPos;
        private PillowSeatEntity stoneSeat;
        private PillowSeatEntity diamondSeat;
        private PillowProjectileEntity stoneProjectile;
        private PillowProjectileEntity diamondProjectile;
        private Pig hitTarget;
        private ItemEntity stoneReturn;
        private ItemEntity diamondReturn;
        private UUID expectedSeatId;
        private UUID expectedStoneProjectileId;
        private UUID expectedDiamondProjectileId;
        private UUID expectedTargetId;
        private UUID expectedStoneReturnId;
        private UUID expectedDiamondReturnId;
        private float targetHealthBeforeHit;
        private Phase phase = Phase.OBSERVING;
        private int phaseTicks;
        private String failure;
        private boolean cleaned;

        private ActiveScenario(ServerLevel level, ServerPlayer alice, ServerPlayer bob,
                               BlockPos base) {
            this.level = level;
            this.alice = alice;
            this.bob = bob;
            this.base = base;
            this.fixtureBounds = new AABB(base.offset(-8, 0, -12), base.offset(8, 8, 8));
            this.stonePillowPos = base.offset(-1, 1, 0);
            this.diamondPillowPos = base.offset(2, 1, 0);
            this.aliceBefore = PlayerSnapshot.capture(alice);
            this.bobBefore = PlayerSnapshot.capture(bob);
        }

        private static ActiveScenario create(MinecraftServer server) {
            ServerPlayer alice = server.getPlayerList().getPlayerByName("BlindBoxAlice");
            ServerPlayer bob = server.getPlayerList().getPlayerByName("BlindBoxBob");
            if (alice == null || bob == null || alice.serverLevel() != bob.serverLevel()) {
                throw new IllegalStateException("P3 抱枕场景要求 Alice 和 Bob 同时在线同一维度");
            }
            ServerLevel level = alice.serverLevel();
            // 保持在 Alice 已加载的 X/Z 柱，高度固定在常规生成高度之上，避免随机出生地地形干扰。
            BlockPos base = new BlockPos(Mth.floor(alice.getX()), 120, Mth.floor(alice.getZ()));
            ActiveScenario scenario = new ActiveScenario(level, alice, bob, base);
            try {
                scenario.setup();
                return scenario;
            } catch (Exception exception) {
                scenario.cleanup();
                throw exception;
            }
        }

        private void setup() {
            saveAndClearFixture();
            for (int x = -8; x <= 8; x++) {
                for (int z = -12; z <= 8; z++) {
                    level.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            alice.teleportTo(level, base.getX() + 0.5D, base.getY() + 1.0D, base.getZ() + 4.5D, 180.0F, 0.0F);
            bob.teleportTo(level, base.getX() + 4.5D, base.getY() + 1.0D, base.getZ() + 4.5D, 180.0F, 0.0F);

            placePillow(alice, ModItems.STONE_PILLOW.get(), stonePillowPos, ModBlocks.STONE_PILLOW.get());
            placePillow(alice, ModItems.DIAMOND_PILLOW.get(), diamondPillowPos, ModBlocks.DIAMOND_PILLOW.get());
            assertSeatSingleOccupancy();
            assertBreakCleanup();
            launchFullChargeProjectiles();
            alice.containerMenu.broadcastChanges();
            bob.containerMenu.broadcastChanges();
        }

        private void saveAndClearFixture() {
            for (int x = -8; x <= 8; x++) {
                for (int y = 0; y <= 8; y++) {
                    for (int z = -12; z <= 8; z++) {
                        BlockPos pos = base.offset(x, y, z);
                        originalBlocks.put(pos.immutable(), level.getBlockState(pos));
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        private void placePillow(ServerPlayer player, Item item, BlockPos pillowPos, net.minecraft.world.level.block.Block expected) {
            ItemStack stack = new ItemStack(item);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            BlockPos support = pillowPos.below();
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false);
            InteractionResult result = stack.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            if (!result.consumesAction() || !stack.isEmpty() || !level.getBlockState(pillowPos).is(expected)) {
                throw new IllegalStateException("抱枕 BlockItem 真实放置入口未生成约定变体：" + item);
            }
        }

        private void assertSeatSingleOccupancy() {
            BlockState state = level.getBlockState(stonePillowPos);
            if (!(state.getBlock() instanceof PillowBlock pillow) || pillow.variant() != PillowVariant.STONE) {
                throw new IllegalStateException("石抱枕放置后未保留石变体方块");
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(stonePillowPos), Direction.UP, stonePillowPos, false);
            if (!state.use(level, alice, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                throw new IllegalStateException("石抱枕服务端右键没有消费交互");
            }
            stoneSeat = onlySeatAt(stonePillowPos);
            if (!alice.isPassenger() || alice.getVehicle() != stoneSeat || minecraftEntity(stoneSeat).getPassengers().size() != 1) {
                throw new IllegalStateException("石抱枕没有让首位玩家坐上唯一座位");
            }
            if (!state.use(level, bob, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                throw new IllegalStateException("石抱枕第二次服务端右键没有进入生产处理");
            }
            if (onlySeatAt(stonePillowPos) != stoneSeat || minecraftEntity(stoneSeat).getPassengers().size() != 1
                    || !minecraftEntity(stoneSeat).getPassengers().contains(alice) || bob.isPassenger()) {
                throw new IllegalStateException("石抱枕单座位约束失效或为第二位玩家生成了座位");
            }
            expectedSeatId = minecraftEntity(stoneSeat).getUUID();
        }

        private void assertBreakCleanup() {
            BlockState diamondState = level.getBlockState(diamondPillowPos);
            if (!(diamondState.getBlock() instanceof PillowBlock pillow) || pillow.variant() != PillowVariant.DIAMOND) {
                throw new IllegalStateException("钻石抱枕放置后未保留钻石变体方块");
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(diamondPillowPos), Direction.UP, diamondPillowPos, false);
            if (!diamondState.use(level, bob, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                throw new IllegalStateException("钻石抱枕服务端右键没有消费交互");
            }
            diamondSeat = onlySeatAt(diamondPillowPos);
            if (!bob.isPassenger() || bob.getVehicle() != diamondSeat) {
                throw new IllegalStateException("钻石抱枕没有创建可清理座位");
            }
            if (!level.removeBlock(diamondPillowPos, false) || !minecraftEntity(diamondSeat).isRemoved()
                    || !seatsAt(diamondPillowPos).isEmpty()) {
                throw new IllegalStateException("拆除钻石抱枕后没有即时清理座位实体");
            }
            bob.stopRiding();
        }

        private void launchFullChargeProjectiles() {
            stoneProjectile = launchFullCharge(ModItems.STONE_PILLOW.get(), PillowVariant.STONE);
            diamondProjectile = launchFullCharge(ModItems.DIAMOND_PILLOW.get(), PillowVariant.DIAMOND);
            expectedStoneProjectileId = minecraftEntity(stoneProjectile).getUUID();
            expectedDiamondProjectileId = minecraftEntity(diamondProjectile).getUUID();
            // 真实入口已写出最大蓄力速度；仅在观察窗口内冻结，防止网络同步前飞出客户端跟踪范围。
            freezeForObservation(stoneProjectile, base.getX() - 1.0D, base.getY() + 2.0D, base.getZ() - 1.5D);
            freezeForObservation(diamondProjectile, base.getX() + 1.5D, base.getY() + 2.0D, base.getZ() - 1.5D);
        }

        private PillowProjectileEntity launchFullCharge(Item item, PillowVariant expectedVariant) {
            ItemStack stack = new ItemStack(item);
            // 用每变体不同的完整 NBT 证明投掷实体不是只按默认物品返还，而是原样保存单件栈。
            stack.getOrCreateTag().putString("PillowCiVariant", expectedVariant.name());
            alice.setItemInHand(InteractionHand.MAIN_HAND, stack);
            if (!stack.getItem().use(level, alice, InteractionHand.MAIN_HAND).getResult().consumesAction()) {
                throw new IllegalStateException("抱枕 Item 真实空气使用入口被拒绝：" + item);
            }
            int before = projectiles().size();
            stack.getItem().releaseUsing(stack, level, alice,
                    stack.getItem().getUseDuration(stack) - PillowBlockItem.MAX_CHARGE_TICKS);
            alice.stopUsingItem();
            List<PillowProjectileEntity> after = projectiles();
            if (!stack.isEmpty() || after.size() != before + 1) {
                throw new IllegalStateException("抱枕满蓄力投掷没有恰好扣除一个物品并生成一个实体：" + item);
            }
            PillowProjectileEntity projectile = after.stream()
                    .filter(entity -> minecraftProjectile(entity).getOwner() == alice && entity.variant() == expectedVariant)
                    .findFirst().orElseThrow(() -> new IllegalStateException("抱枕投掷实体缺少约定变体或主人"));
            if (minecraftEntity(projectile).getDeltaMovement().length() < PillowBlockItem.MAX_THROW_SPEED - 0.05D) {
                throw new IllegalStateException("抱枕满蓄力投掷速度未达到最大蓄力下限："
                        + minecraftEntity(projectile).getDeltaMovement().length());
            }
            return projectile;
        }

        private void freezeForObservation(PillowProjectileEntity projectile, double x, double y, double z) {
            minecraftEntity(projectile).setNoGravity(true);
            minecraftEntity(projectile).setDeltaMovement(Vec3.ZERO);
            minecraftEntity(projectile).setPos(x, y, z);
        }

        private void tick() {
            if (phase == Phase.FAILED || phase == Phase.RESULT_READY) return;
            phaseTicks++;
            if (phase == Phase.OBSERVING && phaseTicks >= OBSERVATION_TICKS) {
                activateResultPaths();
                phase = Phase.AWAITING_RESULT;
                phaseTicks = 0;
                return;
            }
            if (phase == Phase.AWAITING_RESULT) {
                inspectResultPaths();
                if (phaseTicks > RESULT_TIMEOUT_TICKS) {
                    throw new IllegalStateException("抱枕命中或超时路径未在限定服务端 tick 内完成回收");
                }
            }
        }

        private void activateResultPaths() {
            Pig target = EntityType.PIG.create(level);
            if (target == null) throw new IllegalStateException("无法创建抱枕命中夹具目标");
            target.setNoGravity(true);
            target.setPos(minecraftEntity(stoneProjectile).getX(), minecraftEntity(stoneProjectile).getY() - 0.45D,
                    minecraftEntity(stoneProjectile).getZ() - 3.0D);
            if (!level.addFreshEntity(target)) throw new IllegalStateException("抱枕命中夹具目标未进入服务端世界");
            hitTarget = target;
            expectedTargetId = target.getUUID();
            targetHealthBeforeHit = target.getHealth();
            minecraftEntity(stoneProjectile).setNoGravity(true);
            minecraftEntity(stoneProjectile).setDeltaMovement(new Vec3(0.0D, 0.0D, -1.25D));
            // 此实体仍由真实满蓄力 Item 入口生成；把计时推进到阈值只让超时分支在 CI 时限内可观察。
            minecraftEntity(diamondProjectile).tickCount = PillowProjectileEntity.MAX_FLIGHT_TICKS;
        }

        private void inspectResultPaths() {
            if (!minecraftEntity(stoneProjectile).isRemoved() || !minecraftEntity(diamondProjectile).isRemoved()) return;
            stoneReturn = singleReturned(ModItems.STONE_PILLOW.get());
            diamondReturn = singleReturned(ModItems.DIAMOND_PILLOW.get());
            if (stoneReturn == null || diamondReturn == null) return;
            if (stoneReturn.getItem().getCount() != 1 || diamondReturn.getItem().getCount() != 1
                    || countReturned(ModItems.STONE_PILLOW.get()) != 1 || countReturned(ModItems.DIAMOND_PILLOW.get()) != 1) {
                throw new IllegalStateException("抱枕命中或超时后出现吞物/复制，回收数量不是每变体恰好一件");
            }
            if (!stoneReturn.getItem().hasTag() || !diamondReturn.getItem().hasTag()
                    || !"STONE".equals(stoneReturn.getItem().getTag().getString("PillowCiVariant"))
                    || !"DIAMOND".equals(diamondReturn.getItem().getTag().getString("PillowCiVariant"))) {
                throw new IllegalStateException("抱枕投掷实体没有完整保存并返还变体对应 NBT");
            }
            // impacted()/hitTargetId() 由生产实体同步并持久化；客户端 marker 也必须看到同一状态。
            if (!stoneProjectile.impacted() || !stoneProjectile.hitTargetId().filter(expectedTargetId::equals).isPresent()
                    || hitTarget.getHealth() >= targetHealthBeforeHit) {
                throw new IllegalStateException("石抱枕真实命中没有留下同步命中目标或伤害证据");
            }
            if (diamondProjectile.impacted()) {
                throw new IllegalStateException("钻石抱枕超时路径错误记录为实体命中");
            }
            expectedStoneReturnId = stoneReturn.getUUID();
            expectedDiamondReturnId = diamondReturn.getUUID();
            phase = Phase.RESULT_READY;
            phaseTicks = 0;
            CiTestProbe.LOGGER.info("BLINDBOX_CITEST_P3_PILLOW_SERVER=success seat={} stone={} diamond={} target={} stone_return={} diamond_return={}",
                    expectedSeatId, expectedStoneProjectileId, expectedDiamondProjectileId, expectedTargetId,
                    expectedStoneReturnId, expectedDiamondReturnId);
        }

        private void verifyClientMarkers() throws IOException {
            if (phase == Phase.FAILED) throw new IllegalStateException("P3 抱枕服务端场景已失败：" + failure);
            if (phase != Phase.RESULT_READY) throw new IllegalStateException("P3 抱枕服务端尚未完成命中/超时与守恒核验");
            Path markerDir = markerDirectory();
            verifyMarker(readMarker(markerDir.resolve("client-1-pillow-observed.marker")), "客户端一");
            verifyMarker(readMarker(markerDir.resolve("client-2-pillow-observed.marker")), "客户端二");
        }

        private void verifyMarker(Map<String, String> marker, String clientName) {
            if (!"1".equals(marker.get("schema"))) throw new IllegalStateException(clientName + " marker schema 不匹配");
            assertMarkerUuid(marker, "seat", expectedSeatId, clientName);
            assertMarkerUuid(marker, "stone_projectile", expectedStoneProjectileId, clientName);
            assertMarkerUuid(marker, "diamond_projectile", expectedDiamondProjectileId, clientName);
            assertMarkerUuid(marker, "hit_target", expectedTargetId, clientName);
            assertMarkerUuid(marker, "stone_return", expectedStoneReturnId, clientName);
            assertMarkerUuid(marker, "diamond_return", expectedDiamondReturnId, clientName);
        }

        private static void assertMarkerUuid(Map<String, String> marker, String key, UUID expected, String clientName) {
            String actual = marker.get(key);
            if (actual == null || !expected.toString().equals(actual)) {
                throw new IllegalStateException(clientName + " 未观察到同一 " + key + " UUID，期望=" + expected + "，实际=" + actual);
            }
        }

        private static Path markerDirectory() {
            String configured = System.getenv("BLINDBOX_CITEST_PILLOW_MARKER_DIR");
            if (configured == null || configured.isBlank()) {
                throw new IllegalStateException("缺少受控的 BLINDBOX_CITEST_PILLOW_MARKER_DIR");
            }
            Path directory = Path.of(configured).toAbsolutePath().normalize();
            if (!Files.isDirectory(directory)) throw new IllegalStateException("P3 抱枕 marker 目录不存在：" + directory);
            return directory;
        }

        private static Map<String, String> readMarker(Path marker) throws IOException {
            if (!Files.isRegularFile(marker)) throw new IllegalStateException("缺少客户端真实观察 marker：" + marker);
            Map<String, String> fields = new HashMap<>();
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator <= 0 || separator != line.lastIndexOf('=')) {
                    throw new IllegalStateException("客户端 marker 格式非法：" + marker);
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (key.isBlank() || value.isBlank() || fields.putIfAbsent(key, value) != null) {
                    throw new IllegalStateException("客户端 marker 有空字段或重复字段：" + marker);
                }
            }
            if (fields.size() != 7) throw new IllegalStateException("客户端 marker 字段数量不正确：" + marker);
            return fields;
        }

        private PillowSeatEntity onlySeatAt(BlockPos pos) {
            List<PillowSeatEntity> seats = seatsAt(pos);
            if (seats.size() != 1) throw new IllegalStateException("抱枕位置座位数量不是一个：" + pos + "，实际=" + seats.size());
            return seats.get(0);
        }

        private List<PillowSeatEntity> seatsAt(BlockPos pos) {
            return level.getEntitiesOfClass(PillowSeatEntity.class, new AABB(pos),
                    entity -> minecraftEntity(entity).blockPosition().equals(pos));
        }

        private List<PillowProjectileEntity> projectiles() {
            return level.getEntitiesOfClass(PillowProjectileEntity.class, fixtureBounds.inflate(4.0D));
        }

        private ItemEntity singleReturned(Item item) {
            List<ItemEntity> returns = level.getEntitiesOfClass(ItemEntity.class, fixtureBounds.inflate(4.0D),
                    entity -> entity.getItem().is(item));
            return returns.size() == 1 ? returns.get(0) : null;
        }

        private int countReturned(Item item) {
            return level.getEntitiesOfClass(ItemEntity.class, fixtureBounds.inflate(4.0D),
                    entity -> entity.getItem().is(item)).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
        }

        private void fail(Exception exception) {
            failure = exception.getClass().getSimpleName() + ": " + Objects.toString(exception.getMessage(), "");
            phase = Phase.FAILED;
            CiTestProbe.LOGGER.error("BLINDBOX_CITEST_P3_PILLOW_SERVER=failed {}", failure, exception);
            cleanup();
        }

        private void cleanup() {
            if (cleaned) return;
            cleaned = true;
            if (stoneProjectile != null && !minecraftEntity(stoneProjectile).isRemoved()) minecraftEntity(stoneProjectile).discard();
            if (diamondProjectile != null && !minecraftEntity(diamondProjectile).isRemoved()) minecraftEntity(diamondProjectile).discard();
            if (stoneReturn != null && !stoneReturn.isRemoved()) stoneReturn.discard();
            if (diamondReturn != null && !diamondReturn.isRemoved()) diamondReturn.discard();
            if (hitTarget != null && !hitTarget.isRemoved()) hitTarget.discard();
            if (stoneSeat != null && !minecraftEntity(stoneSeat).isRemoved()) minecraftEntity(stoneSeat).discard();
            if (diamondSeat != null && !minecraftEntity(diamondSeat).isRemoved()) minecraftEntity(diamondSeat).discard();
            for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds.inflate(4.0D),
                    entity -> entity.getItem().is(ModItems.STONE_PILLOW.get()) || entity.getItem().is(ModItems.DIAMOND_PILLOW.get()))) {
                entity.discard();
            }
            alice.stopRiding();
            bob.stopRiding();
            originalBlocks.forEach((pos, state) -> level.setBlock(pos, state, 3));
            aliceBefore.restore(alice, level);
            bobBefore.restore(bob, level);
            alice.containerMenu.broadcastChanges();
            bob.containerMenu.broadcastChanges();
        }
    }

    /**
     * ciTest Jar 与正式 Jar 分离重混淆时，继承自 Minecraft 的方法必须以 Minecraft 基类为调用者，
     * 否则字节码会错误寻找自定义实体上的开发环境方法名。此转换不改变测试对象或行为。
     */
    private static Entity minecraftEntity(Entity entity) {
        return entity;
    }

    private static Projectile minecraftProjectile(Projectile projectile) {
        return projectile;
    }

    private record PlayerSnapshot(double x, double y, double z, float yRot, float xRot, ItemStack mainHand, int selected) {
        private static PlayerSnapshot capture(ServerPlayer player) {
            return new PlayerSnapshot(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                    player.getMainHandItem().copy(), player.getInventory().selected);
        }

        private void restore(ServerPlayer player, ServerLevel level) {
            player.getInventory().selected = selected;
            player.setItemInHand(InteractionHand.MAIN_HAND, mainHand.copy());
            player.teleportTo(level, x, y, z, yRot, xRot);
        }
    }
}
