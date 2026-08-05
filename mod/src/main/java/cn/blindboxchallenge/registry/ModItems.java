package cn.blindboxchallenge.registry;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.item.PackingToolItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlindBoxChallenge.MOD_ID);
    public static final RegistryObject<Item> BLIND_BOX = ITEMS.register("blind_box", BlindBoxItem::new);
    public static final RegistryObject<Item> PACKING_TOOL = ITEMS.register("packing_tool", PackingToolItem::new);

    private ModItems() {}
}
