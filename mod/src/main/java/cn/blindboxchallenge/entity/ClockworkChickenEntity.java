package cn.blindboxchallenge.entity;

import cn.blindboxchallenge.config.ModServerConfig;
import cn.blindboxchallenge.registry.ModEntities;
import cn.blindboxchallenge.registry.ModItems;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

/** 046-D 继承原版 TNT 的 Fuse 保存、同步与爆炸语义，只替换服务端强度及审计字段。 */
public final class ClockworkChickenEntity extends PrimedTnt implements ItemSupplier {
    private static final String ARMED_GAME_TIME = "ArmedGameTime";
    private static final String OWNER_UUID = "OwnerUuid";
    private static final String EXPLOSION_POWER = "ExplosionPower";
    private long armedGameTime;
    private UUID ownerUuid;
    private int explosionPower;

    public ClockworkChickenEntity(EntityType<ClockworkChickenEntity> type, Level level) {
        super(type, level);
    }

    public ClockworkChickenEntity(Level level, LivingEntity owner) {
        this(level, owner.getUUID(), level.getGameTime(), ModServerConfig.CLOCKWORK_CHICKEN_FUSE_TICKS.get(),
                ModServerConfig.CLOCKWORK_CHICKEN_EXPLOSION_POWER.get());
        super.setPos(owner.getX(), owner.getY() + 0.2D, owner.getZ());
    }

    /**
     * 统一的武装状态构造器：生产右键与恢复夹具均只写入实体自身的持久字段，
     * 倒计时和威力一经武装即冻结，之后的配置改动不会改写已经放出的实体。
     */
    public ClockworkChickenEntity(Level level, UUID ownerUuid, long armedGameTime, int fuse, int explosionPower) {
        this(ModEntities.CLOCKWORK_CHICKEN.get(), level);
        this.ownerUuid = ownerUuid;
        this.armedGameTime = armedGameTime;
        this.explosionPower = explosionPower;
        super.setFuse(fuse);
    }

    public long armedGameTime() { return armedGameTime; }
    public UUID ownerUuid() { return ownerUuid; }
    public int explosionPower() { return explosionPower; }
    /** 以本模组声明的方法暴露原版同步 Fuse，供重混淆后的独立 ciTest 安全调用。 */
    public int fuse() { return super.getFuse(); }
    /** 独立 ciTest 不直接以本子类为 owner 调用原版 Entity 方法，避免生产重混淆后符号失配。 */
    public UUID stableEntityId() { return super.getUUID(); }
    public BlockPos stableBlockPosition() { return super.blockPosition(); }

    @Override
    protected void explode() {
        if (!super.level().isClientSide) {
            super.level().explode(this, super.getX(), super.getY(0.0625D), super.getZ(), explosionPower,
                    Level.ExplosionInteraction.TNT);
        }
    }

    @Nullable
    @Override
    public LivingEntity getOwner() {
        if (ownerUuid == null || !(super.level() instanceof ServerLevel serverLevel)) return null;
        Entity owner = serverLevel.getEntity(ownerUuid);
        return owner instanceof LivingEntity living ? living : null;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putLong(ARMED_GAME_TIME, armedGameTime);
        if (ownerUuid != null) tag.putUUID(OWNER_UUID, ownerUuid);
        tag.putInt(EXPLOSION_POWER, explosionPower);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        armedGameTime = tag.getLong(ARMED_GAME_TIME);
        ownerUuid = tag.hasUUID(OWNER_UUID) ? tag.getUUID(OWNER_UUID) : null;
        explosionPower = tag.contains(EXPLOSION_POWER, net.minecraft.nbt.Tag.TAG_INT)
                ? tag.getInt(EXPLOSION_POWER) : ModServerConfig.CLOCKWORK_CHICKEN_EXPLOSION_POWER.get();
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(ModItems.CLOCKWORK_CHICKEN.get());
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
