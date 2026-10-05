package cn.blindboxchallenge.event;

import cn.blindboxchallenge.service.AudioDownloadLimits;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraftforge.eventbus.api.Event;

/** S2C 播放事件只携带已通过服务端会话和 URL 规则的快照；客户端决定异步下载和自然结束。 */
public final class MusicBoxPlaybackEvent extends Event {
    private final UUID eventId;
    private final String url;
    private final BlockPos source;
    private final long serverGameTime;
    private final AudioDownloadLimits limits;

    public MusicBoxPlaybackEvent(UUID eventId, String url, BlockPos source, long serverGameTime, AudioDownloadLimits limits) {
        this.eventId = eventId;
        this.url = url;
        this.source = source.immutable();
        this.serverGameTime = serverGameTime;
        this.limits = Objects.requireNonNull(limits, "在线音频下载限制不能为空");
    }

    public UUID eventId() { return eventId; }
    public String url() { return url; }
    public BlockPos source() { return source; }
    public long serverGameTime() { return serverGameTime; }
    public AudioDownloadLimits limits() { return limits; }
}
