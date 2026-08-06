package cn.blindboxchallenge.registry;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.item.AdrenalineItem;
import cn.blindboxchallenge.item.BathBucketItem;
import cn.blindboxchallenge.item.BlackKnightTelescopicKnifeItem;
import cn.blindboxchallenge.item.BlindBoxItem;
import cn.blindboxchallenge.item.ChainsawSwordItem;
import cn.blindboxchallenge.item.EggyEyeMaskItem;
import cn.blindboxchallenge.item.FairyWandItem;
import cn.blindboxchallenge.item.LighterItem;
import cn.blindboxchallenge.item.KazooItem;
import cn.blindboxchallenge.item.LongScrewdriverItem;
import cn.blindboxchallenge.item.NailItem;
import cn.blindboxchallenge.item.PackingToolItem;
import cn.blindboxchallenge.item.PaperCupItem;
import cn.blindboxchallenge.item.PickaxeHoeItem;
import cn.blindboxchallenge.item.PurpleToyPickaxeSwordItem;
import cn.blindboxchallenge.item.VodkaItem;
import cn.blindboxchallenge.item.HeadphonesItem;
import cn.blindboxchallenge.item.SafetyExitSignShieldItem;
import cn.blindboxchallenge.item.DecisionCoinItem;
import cn.blindboxchallenge.item.BirthdayCandleItem;
import cn.blindboxchallenge.item.RainbowHoopItem;
import cn.blindboxchallenge.item.RoadBarrierHelmetItem;
import cn.blindboxchallenge.item.YiJinJingItem;
import net.minecraft.core.Direction;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BlindBoxChallenge.MOD_ID);
    public static final RegistryObject<Item> BLIND_BOX = ITEMS.register("blind_box", BlindBoxItem::new);
    public static final RegistryObject<Item> PACKING_TOOL = ITEMS.register("packing_tool", PackingToolItem::new);

    public static final RegistryObject<Item> BLACK_KNIGHT_TELESCOPIC_KNIFE = ITEMS.register("black_knight_telescopic_knife",
            BlackKnightTelescopicKnifeItem::new);
    public static final RegistryObject<Item> PURPLE_TOY_PICKAXE_SWORD = ITEMS.register("purple_toy_pickaxe_sword",
            PurpleToyPickaxeSwordItem::new);
    public static final RegistryObject<Item> ADRENALINE = ITEMS.register("adrenaline", AdrenalineItem::new);
    public static final RegistryObject<Item> VODKA = ITEMS.register("vodka", VodkaItem::new);
    public static final RegistryObject<Item> HEADPHONES = ITEMS.register("headphones", HeadphonesItem::new);
    public static final RegistryObject<Item> SAFETY_EXIT_SIGN_SHIELD = ITEMS.register("safety_exit_sign_shield", SafetyExitSignShieldItem::new);
    public static final RegistryObject<Item> DECISION_COIN = ITEMS.register("decision_coin", DecisionCoinItem::new);
    public static final RegistryObject<Item> BIRTHDAY_CANDLE = ITEMS.register("birthday_candle", BirthdayCandleItem::new);
    public static final RegistryObject<Item> RAINBOW_HOOP = ITEMS.register("rainbow_hoop", RainbowHoopItem::new);
    public static final RegistryObject<Item> YIJIN_MANUAL = ITEMS.register("yijin_manual", YiJinJingItem::new);
    public static final RegistryObject<Item> ROAD_BARRIER_HELMET = ITEMS.register("road_barrier_helmet",
            () -> new RoadBarrierHelmetItem(ArmorMaterials.IRON));
    public static final RegistryObject<Item> RAT_JERKY_TOTEM = ITEMS.register("rat_jerky_totem", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> LONG_SCREWDRIVER = ITEMS.register("long_screwdriver", LongScrewdriverItem::new);
    public static final RegistryObject<Item> PICKAXE_HOE = ITEMS.register("pickaxe_hoe", PickaxeHoeItem::new);
    public static final RegistryObject<Item> LIGHTER = ITEMS.register("lighter", LighterItem::new);
    public static final RegistryObject<Item> BATH_BUCKET = ITEMS.register("bath_bucket", BathBucketItem::new);
    public static final RegistryObject<Item> GLOW_STICK = ITEMS.register("glow_stick",
            () -> new StandingAndWallBlockItem(ModBlocks.GLOW_STICK.get(), ModBlocks.GLOW_STICK_WALL.get(),
                    new Item.Properties(), Direction.DOWN));
    public static final RegistryObject<Item> BML_CHEER_STICK = ITEMS.register("bml_cheer_stick",
            () -> new StandingAndWallBlockItem(ModBlocks.BML_CHEER_STICK.get(), ModBlocks.BML_CHEER_STICK_WALL.get(),
                    new Item.Properties(), Direction.DOWN));
    public static final RegistryObject<Item> PAPER_CUP = ITEMS.register("paper_cup", PaperCupItem::new);
    public static final RegistryObject<Item> KAZOO = ITEMS.register("kazoo", KazooItem::new);
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

    public static final RegistryObject<Item> NAIL_ART = ITEMS.register("nail_art", NailItem::new);
    public static final RegistryObject<Item> PINK_BUTTERFLY_WINGS = ITEMS.register("pink_butterfly_wings",
            () -> new ElytraItem(new Item.Properties().durability(432)));
    public static final RegistryObject<Item> TOY_KNIFE = ITEMS.register("toy_knife",
            () -> new SwordItem(Tiers.WOOD, 1, -2.4F, new Item.Properties().durability(Tiers.WOOD.getUses())));
    public static final RegistryObject<Item> CHAINSAW_SWORD = ITEMS.register("chainsaw_sword", ChainsawSwordItem::new);
    public static final RegistryObject<Item> EGGY_EYE_MASK = ITEMS.register("eggy_eye_mask",
            () -> new EggyEyeMaskItem(ArmorMaterials.LEATHER));
    public static final RegistryObject<Item> WENXU_STANDEE = ITEMS.register("wenxu_standee",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> CAT_DOLL = ITEMS.register("cat_doll", ModItems::collectible);
    public static final RegistryObject<Item> FACE_MASK = ITEMS.register("face_mask",
            () -> new ArmorItem(ArmorMaterials.LEATHER, ArmorItem.Type.HELMET, new Item.Properties()));

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
