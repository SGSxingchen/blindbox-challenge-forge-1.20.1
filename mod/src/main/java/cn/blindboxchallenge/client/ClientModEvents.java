package cn.blindboxchallenge.client;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.item.BlackKnightTelescopicKnifeItem;
import cn.blindboxchallenge.item.PurpleToyPickaxeSwordItem;
import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.registry.ModMenus;
import cn.blindboxchallenge.registry.ModEntities;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;

@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ClientAbilityKeyEvents.doubleJumpKey());
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.THROWN_PILLOW.get(), ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLOW_SEAT.get(), PillowSeatRenderer::new);
        event.registerEntityRenderer(ModEntities.RETURNING_SCISSORS.get(), ThrownItemRenderer::new);
    }

    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.PACKING_MENU.get(), PackingScreen::new);
            // 仅客户端渲染谓词：生产物品类完全不引用客户端类型，只读取服务器已同步的 NBT。
            ItemProperties.register(ModItems.BLACK_KNIGHT_TELESCOPIC_KNIFE.get(),
                    new ResourceLocation(BlindBoxChallenge.MOD_ID, "extended"),
                    (stack, level, entity, seed) -> BlackKnightTelescopicKnifeItem.isExtended(stack) ? 1.0F : 0.0F);
            ItemProperties.register(ModItems.PURPLE_TOY_PICKAXE_SWORD.get(),
                    new ResourceLocation(BlindBoxChallenge.MOD_ID, "sword_form"),
                    (stack, level, entity, seed) -> PurpleToyPickaxeSwordItem.isPickaxeForm(stack) ? 0.0F : 1.0F);
        });
    }
    private ClientModEvents() {}
}
