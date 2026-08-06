package cn.blindboxchallenge.registry;

import cn.blindboxchallenge.BlindBoxChallenge;
import cn.blindboxchallenge.menu.PackingMenu;
import cn.blindboxchallenge.menu.LetterEditMenu;
import cn.blindboxchallenge.menu.DeathNoteMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, BlindBoxChallenge.MOD_ID);
    public static final RegistryObject<MenuType<PackingMenu>> PACKING_MENU = MENUS.register("packing_menu", () -> IForgeMenuType.create(PackingMenu::new));
    public static final RegistryObject<MenuType<LetterEditMenu>> LETTER_EDIT_MENU = MENUS.register("letter_edit_menu",
            () -> IForgeMenuType.create(LetterEditMenu::new));
    public static final RegistryObject<MenuType<DeathNoteMenu>> DEATH_NOTE_MENU = MENUS.register("death_note_menu",
            () -> IForgeMenuType.create(DeathNoteMenu::new));

    private ModMenus() {}
}
