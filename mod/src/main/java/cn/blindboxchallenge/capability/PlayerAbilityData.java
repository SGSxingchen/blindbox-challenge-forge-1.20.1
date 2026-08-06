package cn.blindboxchallenge.capability;

import net.minecraft.nbt.CompoundTag;

/** 只保存可持久恢复的事实；本次滞空二段跳使用次数是临时服务端状态。 */
public final class PlayerAbilityData {
    private static final String LEARNED_YIJIN = "learned_yijin";
    private static final String NEXT_DOUBLE_JUMP_TICK = "next_double_jump_tick";
    private boolean learnedYiJin;
    private boolean usedDoubleJump;
    private long nextDoubleJumpTick;

    public boolean hasLearnedYiJin() { return learnedYiJin; }
    public void setLearnedYiJin(boolean learnedYiJin) { this.learnedYiJin = learnedYiJin; }
    public boolean hasUsedDoubleJump() { return usedDoubleJump; }
    public void setUsedDoubleJump(boolean usedDoubleJump) { this.usedDoubleJump = usedDoubleJump; }
    public boolean isDoubleJumpOnCooldown(long gameTime) { return gameTime < nextDoubleJumpTick; }
    public long nextDoubleJumpTick() { return nextDoubleJumpTick; }
    public void setNextDoubleJumpTick(long nextDoubleJumpTick) { this.nextDoubleJumpTick = Math.max(0L, nextDoubleJumpTick); }

    public void copyPersistentFrom(PlayerAbilityData source) {
        learnedYiJin = source.learnedYiJin;
        usedDoubleJump = false;
        nextDoubleJumpTick = source.nextDoubleJumpTick;
    }

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(LEARNED_YIJIN, learnedYiJin);
        tag.putLong(NEXT_DOUBLE_JUMP_TICK, nextDoubleJumpTick);
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        learnedYiJin = tag.getBoolean(LEARNED_YIJIN);
        usedDoubleJump = false;
        nextDoubleJumpTick = Math.max(0L, tag.getLong(NEXT_DOUBLE_JUMP_TICK));
    }
}
