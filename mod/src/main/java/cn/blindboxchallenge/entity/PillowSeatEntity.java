package cn.blindboxchallenge.entity;

import cn.blindboxchallenge.block.PillowBlock;
import cn.blindboxchallenge.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkHooks;

/** 不可见的单人座位；方块移除、无人、断线、死亡或换维后都会在服务端回收。 */
public final class PillowSeatEntity extends Entity {
    private static final EntityDataAccessor<Integer> DATA_VARIANT =
            SynchedEntityData.defineId(PillowSeatEntity.class, EntityDataSerializers.INT);
    private static final String VARIANT_TAG = "PillowVariant";

    public PillowSeatEntity(EntityType<PillowSeatEntity> entityType, Level level) {
        super(entityType, level);
        noPhysics = true;
    }

    public PillowSeatEntity(Level level) {
        this(ModEntities.PILLOW_SEAT.get(), level);
    }

    public static PillowSeatEntity findOrCreate(Level level, BlockPos pos, PillowVariant variant) {
        for (PillowSeatEntity seat : level.getEntities(ModEntities.PILLOW_SEAT.get(), new AABB(pos),
                entity -> entity.blockPosition().equals(pos))) {
            if (!seat.isRemoved()) return seat;
        }
        PillowSeatEntity seat = new PillowSeatEntity(level);
        seat.setVariant(variant);
        seat.setPos(pos.getX() + 0.5D, pos.getY() + 0.34D, pos.getZ() + 0.5D);
        return level.addFreshEntity(seat) ? seat : null;
    }

    public static void removeAt(Level level, BlockPos pos) {
        for (PillowSeatEntity seat : level.getEntities(ModEntities.PILLOW_SEAT.get(), new AABB(pos),
                entity -> entity.blockPosition().equals(pos))) {
            seat.discard();
        }
    }

    /** 断线、死亡、换维等玩家生命周期事件必须立即拆除其唯一抱枕座位。 */
    public static void releasePassenger(Entity passenger) {
        if (passenger.getVehicle() instanceof PillowSeatEntity seat) {
            passenger.stopRiding();
            if (!seat.level().isClientSide) seat.discard();
        }
    }

    public PillowVariant variant() {
        return PillowVariant.fromSerializedId(entityData.get(DATA_VARIANT));
    }

    public void setVariant(PillowVariant variant) {
        entityData.set(DATA_VARIANT, variant.serializedId());
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_VARIANT, PillowVariant.STONE.serializedId());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setVariant(PillowVariant.fromSerializedId(tag.getInt(VARIANT_TAG)));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt(VARIANT_TAG, variant().serializedId());
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide
                && (!(level().getBlockState(blockPosition()).getBlock() instanceof PillowBlock) || getPassengers().isEmpty())) {
            discard();
        }
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty();
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
    public double getPassengersRidingOffset() {
        // Player 的乘骑偏移为 -0.35；与座位自身高度相加后正好落在 7/16 高抱枕表面。
        return 0.45D;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
