package cn.blindboxchallenge.citest;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * 只打入 ciTestJar 的探针入口。后续动态 job 必须加载此独立模组，
 * 不得把它放进正式 blindboxchallenge jar。
 */
@Mod(CiTestProbe.MOD_ID)
public final class CiTestProbe {
    public static final String MOD_ID = "blindboxchallenge_citest";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CiTestProbe() {
        LOGGER.info("盲盒挑战 CI 探针已注册");
    }
}
