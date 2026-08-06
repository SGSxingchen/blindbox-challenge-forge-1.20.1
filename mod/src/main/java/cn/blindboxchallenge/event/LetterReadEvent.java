package cn.blindboxchallenge.event;

import java.util.UUID;
import net.minecraftforge.eventbus.api.Event;

/** S2C 信件数据事件只含已验证的纯文本，客户端订阅者决定如何显示。 */
public final class LetterReadEvent extends Event {
    private final UUID instanceId;
    private final int revision;
    private final String body;

    public LetterReadEvent(UUID instanceId, int revision, String body) {
        this.instanceId = instanceId;
        this.revision = revision;
        this.body = body;
    }

    public UUID instanceId() { return instanceId; }
    public int revision() { return revision; }
    public String body() { return body; }
}
