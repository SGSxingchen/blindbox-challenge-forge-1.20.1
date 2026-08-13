import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MOD = ROOT / "mod"
RESOURCES = MOD / "src/main/resources"
JAVA = MOD / "src/main/java/cn/blindboxchallenge"


class GeckoLib模型接入测试(unittest.TestCase):
    def test_依赖与模组元数据锁定官方版本(self):
        properties = (MOD / "gradle.properties").read_text(encoding="utf-8")
        build = (MOD / "build.gradle").read_text(encoding="utf-8")
        mods = (RESOURCES / "META-INF/mods.toml").read_text(encoding="utf-8")
        self.assertIn("geckolib_version=4.8.4", properties)
        self.assertIn("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/", build)
        self.assertIn('implementation fg.deobf("software.bernie.geckolib:geckolib-forge-1.20.1:${geckolib_version}")', build)
        self.assertIn('modId="geckolib"', mods)
        self.assertIn('versionRange="[4.8.4,5)"', mods)
        self.assertIn('side="BOTH"', mods)

    def test_geo资源使用英文标识符与原人工骨骼(self):
        cases = {
            "music_box.geo.json": ("geometry.music_box", "bone"),
            "road_barrier_helmet.geo.json": ("geometry.road_barrier_helmet", "Head"),
        }
        for filename, (identifier, bone) in cases.items():
            with self.subTest(filename=filename):
                payload = json.loads((RESOURCES / "assets/blindboxchallenge/geo" / filename).read_text(encoding="utf-8"))
                geometry = payload["minecraft:geometry"][0]
                self.assertEqual(identifier, geometry["description"]["identifier"])
                self.assertIn(bone, {entry["name"] for entry in geometry["bones"]})

    def test_渲染器注册与模型资源路径闭合(self):
        client_events = (JAVA / "client/ClientModEvents.java").read_text(encoding="utf-8")
        helmet = (JAVA / "item/RoadBarrierHelmetItem.java").read_text(encoding="utf-8")
        block_entity = (JAVA / "blockentity/MusicBoxBlockEntity.java").read_text(encoding="utf-8")
        self.assertIn("registerBlockEntityRenderer(ModBlockEntities.MUSIC_BOX.get(), MusicBoxRenderer::new)", client_events)
        self.assertIn("GeoItem", helmet)
        self.assertIn("RoadBarrierHelmetRenderer", helmet)
        self.assertIn("GeoBlockEntity", block_entity)
        for relative in (
            "assets/blindboxchallenge/textures/block/music_box.png",
            "assets/blindboxchallenge/textures/item/road_barrier_helmet.png",
            "assets/blindboxchallenge/animations/music_box.animation.json",
            "assets/blindboxchallenge/animations/road_barrier_helmet.animation.json",
        ):
            self.assertTrue((RESOURCES / relative).is_file(), relative)

    def test_八音盒禁用原版模型避免双重渲染(self):
        music_box_block = (JAVA / "block/MusicBoxBlock.java").read_text(encoding="utf-8")
        self.assertIn("return RenderShape.INVISIBLE;", music_box_block)
        self.assertNotIn("return RenderShape.MODEL;", music_box_block)

    def test_物品态使用Geo模型覆盖背包手持与掉落(self):
        helmet = (JAVA / "item/RoadBarrierHelmetItem.java").read_text(encoding="utf-8")
        music_box_item = (JAVA / "item/MusicBoxBlockItem.java").read_text(encoding="utf-8")
        items = (JAVA / "registry/ModItems.java").read_text(encoding="utf-8")
        self.assertIn("getCustomRenderer()", helmet)
        self.assertIn("RoadBarrierHelmetItemRenderer", helmet)
        self.assertIn("class MusicBoxBlockItem extends BlockItem implements GeoItem", music_box_item)
        self.assertIn("getCustomRenderer()", music_box_item)
        self.assertIn("MusicBoxItemRenderer", music_box_item)
        self.assertIn("new MusicBoxBlockItem(ModBlocks.MUSIC_BOX.get()", items)
        for identifier in ("music_box", "road_barrier_helmet"):
            with self.subTest(identifier=identifier):
                model = json.loads((RESOURCES / f"assets/blindboxchallenge/models/item/{identifier}.json").read_text(encoding="utf-8"))
                self.assertEqual("builtin/entity", model.get("parent"))


if __name__ == "__main__":
    unittest.main()
