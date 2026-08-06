package cn.blindboxchallenge.blockentity;

import cn.blindboxchallenge.registry.ModBlockEntities;
import cn.blindboxchallenge.service.AudioUrlPolicy;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 047-B 只持久保存已校验 URL、实例 UUID 与修订号；播放事件本身不保存，重登绝不补播。 */
public final class MusicBoxBlockEntity extends BlockEntity {
    private static final String INSTANCE_ID = "instance_id";
    private static final String URL = "url";
    private static final String REVISION = "revision";
    private UUID instanceId = UUID.randomUUID();
    private String url = "";
    private int revision;

    public MusicBoxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MUSIC_BOX.get(), pos, state);
    }

    public UUID instanceId() { return instanceId; }
    public String url() { return url; }
    public int revision() { return revision; }
    public boolean configured() { return !url.isBlank(); }

    public void setUrl(String url) {
        this.url = url;
        revision++;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putUUID(INSTANCE_ID, instanceId);
        tag.putString(URL, url);
        tag.putInt(REVISION, revision);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        instanceId = tag.hasUUID(INSTANCE_ID) ? tag.getUUID(INSTANCE_ID) : UUID.randomUUID();
        String loadedUrl = tag.getString(URL);
        if (loadedUrl.length() > AudioUrlPolicy.MAX_URL_LENGTH) loadedUrl = "";
        try {
            url = loadedUrl.isBlank() ? "" : AudioUrlPolicy.normalizeHttpsUrl(loadedUrl);
        } catch (IllegalArgumentException ignored) {
            url = "";
        }
        revision = Math.max(0, tag.getInt(REVISION));
    }
}
