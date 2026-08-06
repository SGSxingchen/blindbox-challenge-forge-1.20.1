package cn.blindboxchallenge.service;

import cn.blindboxchallenge.config.ModServerConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;

/** 011 的所有范围、候选上限和繁殖结算均在逻辑服务端完成。 */
public final class PigBreedingService {
    public static final double RADIUS_BLOCKS = 10.0D;

    private PigBreedingService() {}

    /**
     * 引擎填充候选列表时即应用最大数量，AABB 只作粗筛，精确距离仍按 10 格球形判断。
     * 每对成年且可恋爱的猪使用原版繁殖 API，不读取或消耗玩家食物。
     */
    public static BreedingResult breedNearby(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        int maximum = ModServerConfig.EFFICIENT_PIG_MAX_SCANNED.get();
        List<Pig> scanned = new ArrayList<>();
        level.getEntities(EntityType.PIG, player.getBoundingBox().inflate(RADIUS_BLOCKS), pig -> pig.isAlive()
                        && pig.distanceToSqr(player) <= RADIUS_BLOCKS * RADIUS_BLOCKS,
                scanned, maximum);
        scanned.sort(Comparator.comparing(pig -> pig.getUUID().toString()));
        List<Pig> eligible = scanned.stream().filter(Pig::canFallInLove).toList();
        int bredPairs = 0;
        for (int index = 0; index + 1 < eligible.size(); index += 2) {
            Pig first = eligible.get(index);
            Pig second = eligible.get(index + 1);
            first.setInLove(player);
            second.setInLove(player);
            if (first.canMate(second)) {
                first.spawnChildFromBreeding(level, second);
                bredPairs++;
            }
        }
        return new BreedingResult(scanned.size(), eligible.size(), bredPairs);
    }

    public record BreedingResult(int scannedPigCount, int eligiblePigCount, int bredPairCount) {}
}
