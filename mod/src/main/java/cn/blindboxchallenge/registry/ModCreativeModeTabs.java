package cn.blindboxchallenge.registry;

import cn.blindboxchallenge.BlindBoxChallenge;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** 玩家可见物品的唯一创造模式入口；实际条目始终来自 ModItems 的注册顺序。 */
public final class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB,
            BlindBoxChallenge.MOD_ID);

    public static final RegistryObject<CreativeModeTab> BLIND_BOX_CHALLENGE = CREATIVE_MODE_TABS.register("blind_box_challenge",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.blindboxchallenge"))
                    .icon(() -> new ItemStack(ModItems.BLIND_BOX.get()))
                    .displayItems((parameters, output) -> ModItems.playerCreativeEntries().forEach(entry -> output.accept(entry.get())))
                    .build());

    private ModCreativeModeTabs() {}
}
