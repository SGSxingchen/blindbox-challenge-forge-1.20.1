package cn.blindboxchallenge.client;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.item.BlackKnightTelescopicKnifeItem;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.item.PurpleToyPickaxeSwordItem;
import cn.blindboxchallenge.registry.ModItems;
import cn.blindboxchallenge.registry.ModMenus;
import cn.blindboxchallenge.registry.ModEntities;
import cn.blindboxchallenge.registry.ModBlockEntities;
import cn.blindboxchallenge.client.MusicBoxScreen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelResourceLocation;

@Mod.EventBusSubscriber(modid = BlindBoxChallenge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    @SubscribeEvent
    public static void registerBlindBoxParts(ModelEvent.RegisterAdditional event) {
        event.register(BlindBoxBakedModel.BODY);
        event.register(BlindBoxBakedModel.LID);
    }

    @SubscribeEvent
    public static void bakeBlindBox(ModelEvent.ModifyBakingResult event) {
        var key = new ModelResourceLocation(BlindBoxChallenge.MOD_ID, "blind_box", "inventory");
        var models = event.getModels();
        var original = models.get(key);
        var body = models.get(BlindBoxBakedModel.BODY);
        var lid = models.get(BlindBoxBakedModel.LID);
        if (original != null && body != null && lid != null) {
            models.put(key, new BlindBoxBakedModel(original, body, lid));
        }
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ClientAbilityKeyEvents.doubleJumpKey());
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.MUSIC_BOX.get(), MusicBoxRenderer::new);
        event.registerEntityRenderer(ModEntities.THROWN_PILLOW.get(), ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.PILLOW_SEAT.get(), PillowSeatRenderer::new);
        event.registerEntityRenderer(ModEntities.RETURNING_SCISSORS.get(), ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.CLOCKWORK_CHICKEN.get(), ThrownItemRenderer::new);
    }

    @SubscribeEvent
    public static void registerPlayerLayers(EntityRenderersEvent.AddLayers event) {
        for (String skin : event.getSkins()) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer != null) renderer.addLayer(new PinkButterflyWingsLayer(renderer, event.getEntityModels()));
        }
    }

    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.PACKING_MENU.get(), PackingScreen::new);
            MenuScreens.register(ModMenus.LETTER_EDIT_MENU.get(), LetterEditScreen::new);
            MenuScreens.register(ModMenus.DEATH_NOTE_MENU.get(), DeathNoteScreen::new);
            MenuScreens.register(ModMenus.MUSIC_BOX_MENU.get(), MusicBoxScreen::new);
            // 仅客户端渲染谓词：生产物品类完全不引用客户端类型，只读取服务器已同步的 NBT。
            ItemProperties.register(ModItems.BLIND_BOX.get(),
                    new ResourceLocation(BlindBoxChallenge.MOD_ID, "opening"),
                    (stack, level, entity, seed) -> BlindBoxItem.getOpeningProgress(stack, entity,
                            Minecraft.getInstance().getFrameTime()));
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
