package cn.blindboxchallenge.entity;

import cn.blindboxchallenge.registry.ModEntities;
import cn.blindboxchallenge.registry.ModItems;
import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.slf4j.Logger;

/**
 * 045 的服务端权威投掷实体。
 *
 * <p>此实体只借用 {@link AbstractArrow} 的服务端命中、追踪和伤害基础设施，不继承原版三叉戟的私有返航状态。
 * 投出的单件完整栈和主人 UUID 都会写入实体 NBT；返航时只允许同维在线主人结算，不能结算时保留实体而不静默吞物。</p>
 */
public final class ReturningScissorsEntity extends AbstractArrow implements ItemSupplier {
    /** 与原版三叉戟投掷伤害保持相同的保守基线。 */
    public static final double BASE_THROW_DAMAGE = 8.0D;
    /** 未命中时也不可无限滞留；5 秒后开始返航而非让普通箭过期删除完整栈。 */
    public static final int MAX_FLIGHT_TICKS = 100;
    /** 到主人眼部这一距离内才结算回收，避免远距离直接入包。 */
    public static final double COLLECTION_DISTANCE_SQR = 2.25D;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String STACK_TAG = "ScissorsStack";
    private static final String OWNER_TAG = "ReturnOwner";
    private static final String RETURNING_TAG = "Returning";
    private static final String RETURN_ITEM_TAG = "ReturnItem";
    private static final String FLIGHT_TICKS_TAG = "FlightTicks";
    private static final String HIT_TARGET_TAG = "HitTarget";
    private static final EntityDataAccessor<ItemStack> DATA_STACK =
            SynchedEntityData.defineId(ReturningScissorsEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Boolean> DATA_RETURNING =
            SynchedEntityData.defineId(ReturningScissorsEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_RETURN_ITEM =
            SynchedEntityData.defineId(ReturningScissorsEntity.class, EntityDataSerializers.BOOLEAN);
    /** 主人 UUID 既保存到 NBT，也同步到客户端；客户端仅用于真实观察与渲染，绝不据此结算回收。 */
    private static final EntityDataAccessor<Optional<UUID>> DATA_RETURN_OWNER =
            SynchedEntityData.defineId(ReturningScissorsEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Optional<UUID>> DATA_HIT_TARGET =
            SynchedEntityData.defineId(ReturningScissorsEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private UUID returnOwnerId;
    private int flightTicks;
    private boolean missingStackReported;
    private boolean missingOwnerReported;

    public ReturningScissorsEntity(EntityType<ReturningScissorsEntity> entityType, Level level) {
        super(entityType, level);
        pickup = Pickup.DISALLOWED;
        setBaseDamage(BASE_THROW_DAMAGE);
    }

    public ReturningScissorsEntity(Level level, LivingEntity owner, ItemStack stack) {
        this(ModEntities.RETURNING_SCISSORS.get(), level);
        setReturnOwner(owner);
        setScissorsStack(stack);
    }

    /** 仅由逻辑服务端的物品释放入口设置；同时保留原版 Projectile owner 与可恢复 UUID。 */
    public void setReturnOwner(LivingEntity owner) {
        setOwner(owner);
        returnOwnerId = owner.getUUID();
        entityData.set(DATA_RETURN_OWNER, Optional.of(returnOwnerId));
        // AbstractArrow#setOwner 会按玩家模式更改拾取策略；本实体只能由服务端返航逻辑结算。
        pickup = Pickup.DISALLOWED;
    }

    /** 保存投出瞬间的完整单件 NBT，不以注册默认物品替换自定义名称、耐久或附魔。 */
    public void setScissorsStack(ItemStack stack) {
        entityData.set(DATA_STACK, stack.copyWithCount(1));
    }

    /** 创造模式已有原栈时关闭返还，避免生成第二把剪刀。 */
    public void setReturnItem(boolean returnItem) {
        entityData.set(DATA_RETURN_ITEM, returnItem);
    }

    public boolean shouldReturnItem() {
        return entityData.get(DATA_RETURN_ITEM);
    }

    public boolean isReturning() {
        return entityData.get(DATA_RETURNING);
    }

    /** 客户端可只读比对真实投掷主人，服务端仍以该 UUID 查询同维在线主人。 */
    public Optional<UUID> returnOwnerId() {
        return entityData.get(DATA_RETURN_OWNER);
    }

    /** 命中目标 UUID 仅作客户端观察；伤害与回收绝不由客户端驱动。 */
    public Optional<UUID> hitTargetId() {
        return entityData.get(DATA_HIT_TARGET);
    }

    /** 返回副本，禁止外部持有并修改实体的受同步完整栈。 */
    public ItemStack storedStack() {
        return entityData.get(DATA_STACK).copy();
    }

    @Override
    public ItemStack getItem() {
        return storedStack();
    }

    @Override
    protected ItemStack getPickupItem() {
        return storedStack();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(DATA_STACK, ItemStack.EMPTY);
        entityData.define(DATA_RETURNING, false);
        entityData.define(DATA_RETURN_ITEM, true);
        entityData.define(DATA_RETURN_OWNER, Optional.empty());
        entityData.define(DATA_HIT_TARGET, Optional.empty());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ItemStack stack = storedStack();
        if (!stack.isEmpty()) tag.put(STACK_TAG, stack.save(new CompoundTag()));
        if (returnOwnerId != null) tag.putUUID(OWNER_TAG, returnOwnerId);
        tag.putBoolean(RETURNING_TAG, isReturning());
        tag.putBoolean(RETURN_ITEM_TAG, shouldReturnItem());
        tag.putInt(FLIGHT_TICKS_TAG, flightTicks);
        hitTargetId().ifPresent(targetId -> tag.putUUID(HIT_TARGET_TAG, targetId));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setScissorsStack(tag.contains(STACK_TAG, Tag.TAG_COMPOUND)
                ? ItemStack.of(tag.getCompound(STACK_TAG)) : ItemStack.EMPTY);
        returnOwnerId = tag.hasUUID(OWNER_TAG) ? tag.getUUID(OWNER_TAG) : null;
        entityData.set(DATA_RETURN_OWNER, returnOwnerId == null ? Optional.empty() : Optional.of(returnOwnerId));
        entityData.set(DATA_RETURNING, tag.getBoolean(RETURNING_TAG));
        setReturnItem(!tag.contains(RETURN_ITEM_TAG) || tag.getBoolean(RETURN_ITEM_TAG));
        flightTicks = Math.max(0, tag.getInt(FLIGHT_TICKS_TAG));
        entityData.set(DATA_HIT_TARGET, tag.hasUUID(HIT_TARGET_TAG)
                ? Optional.of(tag.getUUID(HIT_TARGET_TAG)) : Optional.empty());
        if (isReturning()) {
            setNoGravity(true);
            setNoPhysics(true);
        }
        pickup = Pickup.DISALLOWED;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (level().isClientSide || isReturning()) return;
        Entity target = result.getEntity();
        Entity owner = getOwner();
        Entity damageOwner = owner == null ? this : owner;
        float damage = (float) getBaseDamage();
        if (target instanceof LivingEntity livingTarget) {
            damage += EnchantmentHelper.getDamageBonus(storedStack(), livingTarget.getMobType());
        }
        if (target.hurt(damageSources().trident(this, damageOwner), damage)) {
            if (target instanceof LivingEntity livingTarget) {
                if (owner instanceof LivingEntity livingOwner) EnchantmentHelper.doPostHurtEffects(livingTarget, livingOwner);
                doPostHurtEffects(livingTarget);
            }
        }
        entityData.set(DATA_HIT_TARGET, Optional.of(target.getUUID()));
        level().playSound(null, blockPosition(), SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, 1.0F, 1.0F);
        beginReturn();
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (level().isClientSide || isReturning()) return;
        super.onHitBlock(result);
        beginReturn();
    }

    @Override
    public void tick() {
        if (!isReturning()) {
            super.tick();
            if (!level().isClientSide && !isRemoved() && ++flightTicks >= MAX_FLIGHT_TICKS) beginReturn();
            return;
        }

        // AbstractArrow 的落地/拾取循环不再适用于返航阶段；仍执行 Entity 的基础 tick 以维护生命周期和同步。
        baseTick();
        if (!level().isClientSide) tickReturnOnServer();
    }

    private void beginReturn() {
        if (isReturning() || isRemoved()) return;
        entityData.set(DATA_RETURNING, true);
        setNoGravity(true);
        setNoPhysics(true);
        setDeltaMovement(Vec3.ZERO);
        level().playSound(null, blockPosition(), SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    private void tickReturnOnServer() {
        ItemStack stack = storedStack();
        if (stack.isEmpty() || !stack.is(ModItems.RETURNING_SCISSORS.get())) {
            if (!missingStackReported) {
                missingStackReported = true;
                LOGGER.error("返航剪刀实体 {} 缺失或损坏完整物品栈；保留实体等待人工恢复，绝不静默替换或销毁", getUUID());
            }
            return;
        }

        ServerPlayer owner = findSameDimensionOnlineOwner();
        if (owner == null) {
            if (!missingOwnerReported) {
                missingOwnerReported = true;
                LOGGER.warn("返航剪刀实体 {} 的主人未在线或已换维；保留实体与完整栈，待同维主人恢复后再结算", getUUID());
            }
            return;
        }
        missingOwnerReported = false;

        Vec3 toOwner = owner.getEyePosition().subtract(position());
        if (toOwner.lengthSqr() <= COLLECTION_DISTANCE_SQR) {
            completeReturn(owner, stack);
            return;
        }

        Vec3 velocity = getDeltaMovement().scale(0.95D).add(toOwner.normalize().scale(0.15D));
        setDeltaMovement(velocity);
        move(MoverType.SELF, velocity);
    }

    /**
     * 回收只从实体持有的完整栈结算一次。背包未接收时先确认掉落实体成功加入世界才销毁本实体。
     * 创造模式投掷未扣原栈，因此只销毁飞行实体，绝不能再生成一把。
     */
    private void completeReturn(ServerPlayer owner, ItemStack stack) {
        if (!shouldReturnItem()) {
            discard();
            return;
        }

        ItemStack returnStack = stack.copy();
        if (owner.getInventory().add(returnStack) && returnStack.isEmpty()) {
            discard();
            return;
        }

        // 物品最大堆叠数为 1；满包时不得丢回投掷起点或直接删实体，必须在主人当前位置可拾取地掉落。
        ItemEntity fallback = new ItemEntity(level(), owner.getX(), owner.getY(), owner.getZ(), returnStack);
        fallback.setPickUpDelay(0);
        if (level().addFreshEntity(fallback)) {
            discard();
        } else {
            LOGGER.error("返航剪刀实体 {} 无法生成满包兜底掉落物；保留实体与完整栈，绝不吞物", getUUID());
        }
    }

    /** 只接受在线、未移除、且仍处于当前实体维度的原主人。 */
    private ServerPlayer findSameDimensionOnlineOwner() {
        if (!(level() instanceof ServerLevel serverLevel) || returnOwnerId == null) return null;
        ServerPlayer owner = serverLevel.getServer().getPlayerList().getPlayer(returnOwnerId);
        return owner != null && owner.isAlive() && !owner.isRemoved() && !owner.isSpectator() && owner.level() == level() ? owner : null;
    }

    @Override
    protected void tickDespawn() {
        // 主人离线、换维或加载异常时完整栈留在实体 NBT 中，不能套用普通箭的静默过期销毁。
    }

    @Override
    public void checkBelowWorld() {
        if (!level().isClientSide) {
            // 即使投掷物落入虚空，也先切换到无碰撞返航；主人不可用时实体仍随区块 NBT 保留。
            beginReturn();
        }
        // 客户端也不能套用原版虚空 discard：服务端返航实体仍会继续同步位置和状态。
    }

    @Override
    public void playerTouch(Player player) {
        // 只允许 completeReturn 代表原主人结算；陌生玩家和原主人都不能提前拾取飞行实体。
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
