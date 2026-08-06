package cn.blindboxchallenge.registry;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.item.AdrenalineItem;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.item.LighterItem;
import cn.blindboxchallenge.item.LongScrewdriverItem;
import cn.blindboxchallenge.item.PackingToolItem;
import cn.blindboxchallenge.item.PickaxeHoeItem;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlindBoxChallenge.MOD_ID);
    public static final RegistryObject<Item> BLIND_BOX = ITEMS.register("blind_box", BlindBoxItem::new);
    public static final RegistryObject<Item> PACKING_TOOL = ITEMS.register("packing_tool", PackingToolItem::new);

    public static final RegistryObject<Item> ADRENALINE = ITEMS.register("adrenaline", AdrenalineItem::new);
    public static final RegistryObject<Item> RAT_JERKY_TOTEM = ITEMS.register("rat_jerky_totem", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> LONG_SCREWDRIVER = ITEMS.register("long_screwdriver", LongScrewdriverItem::new);
    public static final RegistryObject<Item> PICKAXE_HOE = ITEMS.register("pickaxe_hoe", PickaxeHoeItem::new);
    public static final RegistryObject<Item> LIGHTER = ITEMS.register("lighter", LighterItem::new);
    public static final RegistryObject<Item> TRUFFLE_HAM_CRACKER = ITEMS.register("truffle_ham_cracker", () -> food(2, 0.1F));
    public static final RegistryObject<Item> POTATO_SNACK = ITEMS.register("potato_snack", () -> food(8, 0.8F));
    public static final RegistryObject<Item> RATION_PACK = ITEMS.register("ration_pack", () -> food(20, 1.0F));
    public static final RegistryObject<Item> SUN_CANDY = ITEMS.register("sun_candy", () -> food(2, 0.1F));

    private static Item food(int nutrition, float saturation) {
        return new Item(new Item.Properties().food(new FoodProperties.Builder().nutrition(nutrition).saturationMod(saturation).build()));
    }

    private ModItems() {}
}
