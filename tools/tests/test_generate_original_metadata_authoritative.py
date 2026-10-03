import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import generate_original_metadata as 被测模块


原名称期望 = {
    'blind_box': ('盲盒', 'Blind Box'),
    'packing_tool': ('奖池打包道具', 'Prize Pool Packer'),
    'letter': ('信件', 'Letter'),
    'death_note': ('死亡笔记', 'Death Note'),
    'clockwork_chicken': ('发条小黄鸡', 'Clockwork Chick'),
    'music_box': ('木质手工八音盒', 'Handcrafted Wooden Music Box'),
    'anywhere_door': ('任意门', 'Anywhere Door'),
    'safety_landing': ('安全落点', 'Safety Landing'),
    'black_knight_telescopic_knife': ('黑武士伸缩刀', 'Black Knight Telescopic Knife'),
    'purple_toy_pickaxe_sword': ('紫色玩具钻石镐 / 剑', 'Purple Toy Diamond Pickaxe / Sword'),
    'adrenaline': ('盐酸肾上腺素', 'Adrenaline'),
    'rat_jerky_totem': ('奶龙老鼠干', 'Rat Jerky Totem'),
    'long_screwdriver': ('一米长螺丝刀', 'One-Metre Screwdriver'),
    'pickaxe_hoe': ('镐锄一体', 'Pickaxe Hoe'),
    'lighter': ('打火机', 'Lighter'),
    'bath_bucket': ('70cm 泡澡桶', '70 cm Bath Bucket'),
    'glow_stick': ('荧光棒', 'Glow Stick'),
    'bml_cheer_stick': ('BML 应援棒', 'BML Cheer Stick'),
    'paper_cup': ('纸杯', 'Paper Cup'),
    'truffle_ham_cracker': ('黑松露火腿苏打饼干', 'Truffle Ham Cracker'),
    'potato_snack': ('呀土豆零食', 'Potato Snack'),
    'ration_pack': ('军粮包', 'Ration Pack'),
    'sun_candy': ('太阳糖', 'Sun Candy'),
    'fairy_wand': ('仙女棒', 'Fairy Wand'),
    'toy_car': ('风火轮玩具车', 'Toy Car'),
    'million_pound_note': ('百万英镑', 'Million Pound Note'),
    'math_exam_paper': ('小升初模拟卷数学品', 'Math Practice Paper'),
    'wang_lixin_badge': ('王立新徽章', 'Wang Lixin Badge'),
    'flowing_black_flag': ('流动黑旗', 'Flowing Black Flag'),
    'shark_dagger_pillow': ('鲨匕抱枕', 'Shark Dagger Pillow'),
    'white_rabbit_candy': ('大白兔奶糖', 'Milk Candy'),
    'deep_sea_fish': ('深海小鱼', 'Deep-Sea Fish Snack'),
    'ham_sausage': ('火腿肠', 'Ham Sausage'),
    'quail_egg': ('鹌鹑蛋', 'Quail Egg'),
    'green_soy_milk': ('哈基米南北绿豆浆', 'Green Soy Milk'),
    'beef_bites': ('牛肉粒一包', 'Beef Bites'),
    'oil_chestnut': ('油板栗', 'Oil Chestnut'),
    'wind_blown_cake': ('风吹饼', 'Wind-blown Cake'),
    'sweet_sour_turkey_noodles': ('糖醋火鸡面', 'Sweet-Sour Turkey Noodles'),
    'sesame_rice_noodles': ('麻酱米线', 'Sesame Rice Noodles'),
    'potato_chips': ('呀土豆', 'Potato Chips'),
    'black_truffle_ham_cracker': ('黑松露火腿苏打饼干', 'Black Truffle Ham Cracker'),
    'magic_crispy_noodles': ('魔法士干脆面', 'Magic Crispy Noodles'),
    'kazoo': ('卡祖笛', 'Kazoo'),
    'nail_art': ('美甲', 'Nail Art'),
    'pink_butterfly_wings': ('粉色蝴蝶翅膀', 'Pink Butterfly Wings'),
    'toy_knife': ('玩具小刀', 'Toy Knife'),
    'chainsaw_sword': ('战锤40k 链锯剑 泰拉之牙', 'Warhammer 40k Chainsaw Sword - Teeth of Terra'),
    'eggy_eye_mask': ('蛋仔眼罩', 'Eggy Eye Mask'),
    'wenxu_standee': ('文绪立牌', 'Wenxu Standee'),
    'cat_doll': ('猫玩偶', 'Cat Doll'),
    'face_mask': ('口罩', 'Face Mask'),
    'vodka': ('伏特加', 'Vodka'),
    'headphones': ('头戴式耳机', 'Headphones'),
    'safety_exit_sign_shield': ('安全出口牌子盾牌', 'Safety Exit Sign Shield'),
    'decision_coin': ('决策币', 'Decision Coin'),
    'birthday_candle': ('生日蜡烛', 'Birthday Candle'),
    'rainbow_hoop': ('彩虹圈', 'Rainbow Hoop'),
    'yijin_manual': ('易筋经', 'Muscle and Tendon Manual'),
    'road_barrier_helmet': ('路障头饰', 'Road Barrier Helmet'),
    'efficient_pig_breeding': ('高效养猪技术', 'Efficient Pig Breeding'),
    'stone_pillow': ('石墩子抱枕', 'Stone Stool Pillow'),
    'diamond_pillow': ('钻石抱枕', 'Diamond Pillow'),
    'returning_scissors': ('刺客伍六七剪刀', 'Scissor Seven Scissors'),
    'abstract_white_figurine': ('小白人', 'Little White Figure'),
    'floor_art_panel': ('蒙娜奶龙画像', 'Mona Nailong Portrait'),
    'neutral_trophy': ('鹿管大赛特等奖奖杯', 'Lu Guan Competition Grand Prize Trophy'),
}


class 权威元数据确定性生成测试(unittest.TestCase):
    def test_四项权威载荷均与规范模板一致(self):
        漂移 = {
            relative for relative in 被测模块.TARGETS
            if (被测模块.RESOURCE_ROOT / relative).read_bytes() != 被测模块.render_template(relative)
        }
        self.assertEqual(set(), 漂移)
        self.assertEqual(set(被测模块.TARGETS), set(被测模块.AUTHORITATIVE_METADATA_PAYLOADS))

    def test_全部67件台账原名及方块实体别名保留(self):
        self.assertEqual(67, len(原名称期望))
        for 下标, locale in enumerate(("zh_cn", "en_us")):
            relative = f"assets/blindboxchallenge/lang/{locale}.json"
            文案 = json.loads(被测模块.render(relative))
            for 标识, 双语 in 原名称期望.items():
                with self.subTest(locale=locale, item=标识):
                    self.assertEqual(双语[下标], 文案[f"item.blindboxchallenge.{标识}"])
                    for 前缀 in ("block", "entity"):
                        键 = f"{前缀}.blindboxchallenge.{标识}"
                        if 键 in 文案:
                            self.assertEqual(双语[下标], 文案[键])
            self.assertIn("itemGroup.blindboxchallenge", 文案)

    def test_菜单提示恢复身份并保持格式参数(self):
        for locale, 期望 in {
            "zh_cn": {
                "menu.blindboxchallenge.packing": "奖池打包",
                "menu.blindboxchallenge.letter_edit": "编辑信件",
                "menu.blindboxchallenge.death_note": "死亡笔记",
                "screen.blindboxchallenge.pack": "打包并生成盲盒",
                "screen.blindboxchallenge.letter_read": "信件",
                "message.blindboxchallenge.death_note_invalid_target": "死亡笔记只接受有效的玩家名。",
                "message.blindboxchallenge.death_note_executed": "死亡笔记已对 %s 执行。",
                "message.blindboxchallenge.door_safety_required": "两扇门各需要且只能需要一个相邻的安全落点。",
                "block.blindboxchallenge.glow_stick_wall": "墙面荧光棒",
                "block.blindboxchallenge.bml_cheer_stick_wall": "墙面 BML 应援棒",
                "entity.blindboxchallenge.thrown_pillow": "投掷抱枕",
                "entity.blindboxchallenge.pillow_seat": "抱枕座位",
            },
            "en_us": {
                "menu.blindboxchallenge.packing": "Prize Pool Packer",
                "menu.blindboxchallenge.letter_edit": "Edit Letter",
                "menu.blindboxchallenge.death_note": "Death Note",
                "screen.blindboxchallenge.pack": "Pack and Create Blind Box",
                "screen.blindboxchallenge.letter_read": "Letter",
                "message.blindboxchallenge.death_note_invalid_target": "The Death Note only accepts a valid player name.",
                "message.blindboxchallenge.death_note_executed": "The Death Note was executed for %s.",
                "message.blindboxchallenge.door_safety_required": "Each door needs exactly one adjacent Safety Landing block.",
                "block.blindboxchallenge.glow_stick_wall": "Wall Glow Stick",
                "block.blindboxchallenge.bml_cheer_stick_wall": "Wall BML Cheer Stick",
                "entity.blindboxchallenge.thrown_pillow": "Thrown Pillow",
                "entity.blindboxchallenge.pillow_seat": "Pillow Seat",
            },
        }.items():
            文案 = json.loads(被测模块.render(f"assets/blindboxchallenge/lang/{locale}.json"))
            for 键, 值 in 期望.items():
                self.assertEqual(值, 文案[键], 键)
            参数数量 = {
                "screen.blindboxchallenge.letter_line": 1,
                "screen.blindboxchallenge.letter_hint": 2,
                "message.blindboxchallenge.death_note_scheduled": 1,
                "message.blindboxchallenge.death_note_executed": 1,
            }
            self.assertEqual(参数数量, {键: 文案[键].count("%s") for 键 in 参数数量})

    def test_临时重建四项与正式文件逐字节一致(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            临时根 = Path(临时目录)
            被测模块.write_authoritative_metadata(临时根)
            for relative in 被测模块.AUTHORITATIVE_METADATA_PAYLOADS:
                self.assertEqual((被测模块.RESOURCE_ROOT / relative).read_bytes(), (临时根 / relative).read_bytes())

    def test_运行时渲染不读取正式资源或output(self):
        relative = next(iter(被测模块.AUTHORITATIVE_METADATA_PAYLOADS))
        with patch.object(Path, "read_bytes", side_effect=AssertionError("不得读取资源文件")):
            内容 = 被测模块.render(relative)
        self.assertEqual(被测模块.decode_authoritative_metadata(relative), 内容)


if __name__ == "__main__":
    unittest.main()
