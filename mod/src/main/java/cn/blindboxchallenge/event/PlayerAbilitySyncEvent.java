package cn.blindboxchallenge.event;

import net.minecraftforge.eventbus.api.Event;

/** 只在收到 S2C 能力快照的物理客户端事件总线上发布，避免公共网络包引用客户端类。 */
public final class PlayerAbilitySyncEvent extends Event {
    private final int entityId;
    private final boolean learnedYiJin;

    public PlayerAbilitySyncEvent(int entityId, boolean learnedYiJin) {
        this.entityId = entityId;
        this.learnedYiJin = learnedYiJin;
    }

    public int entityId() { return entityId; }
    public boolean learnedYiJin() { return learnedYiJin; }
}
