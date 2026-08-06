package cn.blindboxchallenge.registry;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.item.AdrenalineItem;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.item.FairyWandItem;
import cn.blindboxchallenge.item.LighterItem;
import cn.blindboxchallenge.item.LongScrewdriverItem;
import cn.blindboxchallenge.item.PackingToolItem;
import cn.blindboxchallenge.item.PickaxeHoeItem;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
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

    public static final RegistryObject<Item> FAIRY_WAND = ITEMS.register("fairy_wand", FairyWandItem::new);
    public static final RegistryObject<Item> TOY_CAR = ITEMS.register("toy_car", ModItems::collectible);
    public static final RegistryObject<Item> MILLION_POUND_NOTE = ITEMS.register("million_pound_note", ModItems::collectible);
    public static final RegistryObject<Item> MATH_EXAM_PAPER = ITEMS.register("math_exam_paper", ModItems::collectible);
    public static final RegistryObject<Item> WANG_LIXIN_BADGE = ITEMS.register("wang_lixin_badge", ModItems::collectible);
    public static final RegistryObject<Item> FLOWING_BLACK_FLAG = ITEMS.register("flowing_black_flag", ModItems::collectible);
    public static final RegistryObject<Item> SHARK_DAGGER_PILLOW = ITEMS.register("shark_dagger_pillow",
            () -> new SwordItem(Tiers.STONE, 3, -2.4F, new Item.Properties().durability(Tiers.STONE.getUses())));

    private static Item collectible() {
        return new Item(new Item.Properties());
    }

    private static Item food(int nutrition, float saturation) {
        return new Item(new Item.Properties().food(new FoodProperties.Builder().nutrition(nutrition).saturationMod(saturation).build()));
    }

    private ModItems() {}
}
