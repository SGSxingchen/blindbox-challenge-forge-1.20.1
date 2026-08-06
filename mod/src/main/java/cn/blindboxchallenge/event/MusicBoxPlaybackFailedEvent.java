package cn.blindboxchallenge.event;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraftforge.eventbus.api.Event;

/** 仅客户端本地失败通知：便于界面/可观测性获知本次在线音频未能下载或解码，绝不回传服务器。 */
public final class MusicBoxPlaybackFailedEvent extends Event {
    private final UUID eventId;
    private final String url;
    private final BlockPos source;

    public MusicBoxPlaybackFailedEvent(UUID eventId, String url, BlockPos source) {
        this.eventId = eventId;
        this.url = url;
        this.source = source.immutable();
    }

    public UUID eventId() { return eventId; }
    public String url() { return url; }
    public BlockPos source() { return source; }
}
