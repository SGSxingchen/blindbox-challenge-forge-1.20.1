package cn.blindboxchallenge.registry;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.menu.PackingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, BlindBoxChallenge.MOD_ID);
    public static final RegistryObject<MenuType<PackingMenu>> PACKING_MENU = MENUS.register("packing_menu", () -> IForgeMenuType.create(PackingMenu::new));

    private ModMenus() {}
}
