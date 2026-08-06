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
    public static final RegistryObject<Item> WHITE_RABBIT_CANDY = ITEMS.register("white_rabbit_candy", () -> food(2, 0.1F));
    public static final RegistryObject<Item> DEEP_SEA_FISH = ITEMS.register("deep_sea_fish", () -> food(2, 0.1F));
    public static final RegistryObject<Item> HAM_SAUSAGE = ITEMS.register("ham_sausage", () -> food(4, 0.3F));
    public static final RegistryObject<Item> QUAIL_EGG = ITEMS.register("quail_egg", () -> food(2, 0.2F));
    public static final RegistryObject<Item> GREEN_SOY_MILK = ITEMS.register("green_soy_milk", () -> food(4, 0.3F));
    public static final RegistryObject<Item> BEEF_BITES = ITEMS.register("beef_bites", () -> food(6, 0.6F));
    public static final RegistryObject<Item> OIL_CHESTNUT = ITEMS.register("oil_chestnut", () -> food(4, 0.3F));
    public static final RegistryObject<Item> WIND_BLOWN_CAKE = ITEMS.register("wind_blown_cake", () -> food(4, 0.3F));
    public static final RegistryObject<Item> SWEET_SOUR_TURKEY_NOODLES = ITEMS.register("sweet_sour_turkey_noodles", () -> food(8, 0.7F));
    public static final RegistryObject<Item> SESAME_RICE_NOODLES = ITEMS.register("sesame_rice_noodles", () -> food(8, 0.6F));
    public static final RegistryObject<Item> POTATO_CHIPS = ITEMS.register("potato_chips", () -> food(6, 0.5F));
    public static final RegistryObject<Item> BLACK_TRUFFLE_HAM_CRACKER = ITEMS.register("black_truffle_ham_cracker", () -> food(2, 0.1F));
    public static final RegistryObject<Item> MAGIC_CRISPY_NOODLES = ITEMS.register("magic_crispy_noodles", () -> food(6, 0.5F));

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
