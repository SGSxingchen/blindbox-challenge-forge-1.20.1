package cn.blindboxchallenge.entity;

import cn.blindboxchallenge.registry.ModEntities;
import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.slf4j.Logger;

/** 008、016 共用投掷实体：完整物品栈、变体与返还标记均可同步、保存和恢复。 */
public final class PillowProjectileEntity extends ThrowableItemProjectile {
    public static final int MAX_FLIGHT_TICKS = 200;
    /** 命中状态至少同步四个服务端 tick，供真实客户端确认同一命中后才结算返还。 */
    public static final int IMPACT_VISIBILITY_TICKS = 4;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final EntityDataAccessor<Integer> DATA_VARIANT =
            SynchedEntityData.defineId(PillowProjectileEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_RETURN_ITEM =
            SynchedEntityData.defineId(PillowProjectileEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_IMPACTED =
            SynchedEntityData.defineId(PillowProjectileEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<UUID>> DATA_HIT_TARGET =
            SynchedEntityData.defineId(PillowProjectileEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final String VARIANT_TAG = "PillowVariant";
    private static final String RETURN_ITEM_TAG = "ReturnItem";
    private static final String FINISHED_TAG = "Finished";
    private static final String IMPACTED_TAG = "Impacted";
    private static final String HIT_TARGET_TAG = "HitTarget";
    private static final String RETURN_DELAY_TAG = "ReturnDelay";
    private boolean finished;
    private int returnDelayTicks;
    private boolean missingStackReported;

    public PillowProjectileEntity(EntityType<? extends PillowProjectileEntity> entityType, Level level) {
        super(entityType, level);
    }

    public PillowProjectileEntity(Level level, LivingEntity owner) {
        super(ModEntities.THROWN_PILLOW.get(), owner, level);
    }

    public PillowProjectileEntity(Level level) {
        this(ModEntities.THROWN_PILLOW.get(), level);
    }

    public void setPillowStack(ItemStack stack) {
        ItemStack onePillow = stack.copyWithCount(1);
        setItem(onePillow);
        setVariant(PillowVariant.fromItem(onePillow));
    }

    public PillowVariant variant() {
        return PillowVariant.fromSerializedId(entityData.get(DATA_VARIANT));
    }

    public void setVariant(PillowVariant variant) {
        entityData.set(DATA_VARIANT, variant.serializedId());
    }

    public void setReturnItem(boolean returnItem) {
        entityData.set(DATA_RETURN_ITEM, returnItem);
    }

    public boolean shouldReturnItem() {
        return entityData.get(DATA_RETURN_ITEM);
    }

    /** 客户端只读的真实命中同步状态；不得据此在客户端结算伤害或返还。 */
    public boolean impacted() {
        return entityData.get(DATA_IMPACTED);
    }

    /** 命中生物时由服务端写入；方块命中保留为空。 */
    public Optional<UUID> hitTargetId() {
        return entityData.get(DATA_HIT_TARGET);
    }

    @Override
    protected Item getDefaultItem() {
        return variant().item();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(DATA_VARIANT, PillowVariant.STONE.serializedId());
        entityData.define(DATA_RETURN_ITEM, true);
        entityData.define(DATA_IMPACTED, false);
        entityData.define(DATA_HIT_TARGET, Optional.empty());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt(VARIANT_TAG, variant().serializedId());
        tag.putBoolean(RETURN_ITEM_TAG, shouldReturnItem());
        tag.putBoolean(FINISHED_TAG, finished);
        tag.putBoolean(IMPACTED_TAG, impacted());
        hitTargetId().ifPresent(targetId -> tag.putUUID(HIT_TARGET_TAG, targetId));
        tag.putInt(RETURN_DELAY_TAG, returnDelayTicks);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setVariant(PillowVariant.fromSerializedId(tag.getInt(VARIANT_TAG)));
        setReturnItem(!tag.contains(RETURN_ITEM_TAG) || tag.getBoolean(RETURN_ITEM_TAG));
        finished = tag.getBoolean(FINISHED_TAG);
        entityData.set(DATA_IMPACTED, tag.getBoolean(IMPACTED_TAG));
        entityData.set(DATA_HIT_TARGET, tag.hasUUID(HIT_TARGET_TAG)
                ? Optional.of(tag.getUUID(HIT_TARGET_TAG)) : Optional.empty());
        returnDelayTicks = tag.contains(RETURN_DELAY_TAG) ? Math.max(0, tag.getInt(RETURN_DELAY_TAG))
                : (finished ? IMPACT_VISIBILITY_TICKS : 0);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (!level().isClientSide && result.getEntity() instanceof LivingEntity target) {
            Entity owner = getOwner();
            target.hurt(damageSources().thrown(this, owner == null ? this : owner), variant().impactDamage());
            entityData.set(DATA_IMPACTED, true);
            entityData.set(DATA_HIT_TARGET, Optional.of(target.getUUID()));
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!level().isClientSide) {
            entityData.set(DATA_IMPACTED, true);
            beginReturn();
        }
    }

    @Override
    public void tick() {
        if (finished) {
            if (!level().isClientSide && returnDelayTicks-- <= 0) tryReturnItem();
            return;
        }
        super.tick();
        if (!level().isClientSide && !isRemoved() && tickCount >= MAX_FLIGHT_TICKS) beginReturn();
    }

    private void beginReturn() {
        if (finished || isRemoved()) return;
        finished = true;
        setNoGravity(true);
        setDeltaMovement(Vec3.ZERO);
        returnDelayTicks = IMPACT_VISIBILITY_TICKS;
    }

    private void tryReturnItem() {
        if (!finished || isRemoved()) return;
        if (!shouldReturnItem()) {
            discard();
            return;
        }
        ItemStack returnStack = getItemRaw().copy();
        if (returnStack.isEmpty()) {
            if (!missingStackReported) {
                missingStackReported = true;
                LOGGER.error("抱枕投掷实体 {} 缺失完整物品栈；保留实体等待人工恢复，绝不以变体静默替换", getUUID());
            }
            return;
        }
        ItemEntity returnedItem = new ItemEntity(level(), getX(), getY(), getZ(), returnStack);
        if (level().addFreshEntity(returnedItem)) discard();
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
