import hashlib
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from pathlib import Path

from PIL import Image

from tools import generate_item_texture_candidates as 被测模块


示例项 = {
    "id": "blind_box",
    "texture": "assets/blindboxchallenge/textures/item/blind_box.png",
    "subject": "盲盒",
    "must_show": "盒盖接缝和抽象问号形色块",
    "palette": ["靛蓝", "紫罗兰", "金黄"],
    "avoid": ["广告背景", "包装", "品牌", "文字", "水印", "人物手持"],
}


class 候选生成测试(unittest.TestCase):
    def test_默认正式根为资源目录(self):
        self.assertEqual(被测模块.仓库根目录 / "mod/src/main/resources", 被测模块.默认正式根)

    def _五十九项(self):
        return [dict(示例项, id=f"item_{i:02d}", texture=f"assets/blindboxchallenge/textures/item/item_{i:02d}.png") for i in range(59)]

    def _准备安装(self, 根: Path):
        清单 = self._五十九项(); 审查 = []
        for i, 项 in enumerate(清单):
            内容 = f"candidate-{i}".encode(); 候选 = 根 / "candidates" / Path(项["texture"]).name
            候选.parent.mkdir(parents=True, exist_ok=True); 候选.write_bytes(内容)
            正式 = 根 / "repo" / 项["texture"]; 正式.parent.mkdir(parents=True, exist_ok=True); 正式.write_bytes(f"old-{i}".encode())
            审查.append({"id": 项["id"], "texture": 项["texture"], "candidate_path": 候选.name,
                         "candidate_sha256": hashlib.sha256(内容).hexdigest(), "status": "pass", "notes": "通过"})
        审查路径 = 根 / "review.json"; 审查路径.write_text(json.dumps(审查), encoding="utf-8")
        return 清单, 审查, 审查路径

    def test_prompt_suffix只允许单个only且写入最终提示词(self):
        解析器 = 被测模块.创建参数解析器()
        参数 = 解析器.parse_args(["--only", "blind_box", "--prompt-suffix", "仅加宽刃部"])
        self.assertEqual("仅加宽刃部", 参数.prompt_suffix)
        with self.assertRaises(SystemExit): 解析器.parse_args(["--all", "--prompt-suffix", "不允许"])
        with self.assertRaises(SystemExit): 解析器.parse_args(["--only", "a", "--only", "b", "--prompt-suffix", "不允许"])

    def test_install_cli逐项拒绝所有生图参数且不写入(self):
        冲突参数 = [
            ["--all"], ["--only", "blind_box"], ["--dry-run"], ["--resume"],
            ["--prompt-suffix", "仅改轮廓"], ["--archive-existing"],
        ]
        with tempfile.TemporaryDirectory() as 临时目录:
            根=Path(临时目录); 审查=根/"review.json"; 审查.write_text("[]",encoding="utf-8")
            for 附加 in 冲突参数:
                输出=根/("out-"+str(len(list(根.iterdir()))))
                命令=[sys.executable,str(被测模块.__file__),"--install-approved",str(审查),"--output-root",str(输出),*附加]
                环境=dict(os.environ, PYTHONUTF8="1")
                结果=subprocess.run(命令,text=True,capture_output=True,encoding="utf-8",errors="strict",env=环境)
                with self.subTest(附加=附加):
                    self.assertEqual(2,结果.returncode); self.assertIn("互斥",结果.stderr); self.assertFalse(输出.exists())

    def test_install_cli允许output_root共存(self):
        参数=被测模块.创建参数解析器().parse_args(["--install-approved","review.json","--output-root","custom-output"])
        self.assertEqual(Path("custom-output"),参数.output_root)

    def test_prompt_suffix附加到命令与metadata(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根=Path(临时目录); 假脚本=根/"fake.py"; 假脚本.write_text("# fake",encoding="utf-8")
            def 运行(命令, **kwargs):
                请求=Path(命令[命令.index("--output")+1]); Image.new("RGB",(8,8),(80,20,150)).save(请求)
                return subprocess.CompletedProcess(命令,0,"","")
            self.assertEqual("成功", 被测模块.生成单项(示例项,根,运行,图像脚本=假脚本,提示词后缀="仅加宽刃部"))
            数据=json.loads((根/"metadata/blind_box.json").read_text(encoding="utf-8"))
            self.assertTrue(数据["最终提示词"].endswith("仅加宽刃部")); self.assertEqual("仅加宽刃部",数据["提示词后缀"])

    def test_archive保留旧四层文件(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根=Path(临时目录)
            for 子,后缀 in (("sources","blind_box.png"),("clean","blind_box.png"),("candidates","blind_box.png"),("metadata","blind_box.json")):
                p=根/子/后缀; p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes((子+"-old").encode())
            版本=被测模块.归档现有版本(示例项,根)
            self.assertIsNotNone(版本)
            self.assertEqual({"source.png","clean.png","candidate.png","metadata.json"},{p.name for p in 版本.iterdir()})

    def test_archive失败时不得调用生图(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根=Path(临时目录); 假脚本=根/"fake.py"; 假脚本.write_text("# fake",encoding="utf-8"); 调用=[]
            with patch.object(被测模块,"归档现有版本",side_effect=OSError("archive failed")):
                状态=被测模块.生成单项(示例项,根,lambda *a,**k: 调用.append(1),图像脚本=假脚本,归档已有=True)
            self.assertEqual("失败",状态); self.assertEqual([],调用)

    def test_archive四层任一缺失均拒绝且不调用生图不改旧文件(self):
        四层=(("sources","blind_box.png"),("clean","blind_box.png"),("candidates","blind_box.png"),("metadata","blind_box.json"))
        for 缺失索引 in range(4):
            with self.subTest(缺失索引=缺失索引),tempfile.TemporaryDirectory() as 临时目录:
                根=Path(临时目录); 快照={}; 假脚本=根/"fake.py"; 假脚本.write_text("# fake",encoding="utf-8"); 调用=[]
                for i,(目录,名称) in enumerate(四层):
                    if i==缺失索引: continue
                    p=根/目录/名称; p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(f"old-{i}".encode()); 快照[p]=p.read_bytes()
                状态=被测模块.生成单项(示例项,根,lambda *a,**k: 调用.append(1),图像脚本=假脚本,归档已有=True)
                self.assertEqual("失败",状态); self.assertEqual([],调用)
                for p,内容 in 快照.items(): self.assertEqual(内容,p.read_bytes())

    def test_install拒绝pending缺项hash错绝对路径和重复(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根=Path(临时目录); 清单,审查,审查路径=self._准备安装(根)
            变体=[]
            x=json.loads(json.dumps(审查)); x[0]["status"]="pending"; 变体.append(x)
            变体.append(审查[:-1])
            x=json.loads(json.dumps(审查)); x[0]["candidate_sha256"]="0"*64; 变体.append(x)
            x=json.loads(json.dumps(审查)); x[0]["candidate_path"]="C:/escape.png"; 变体.append(x)
            x=json.loads(json.dumps(审查)); x[-1]=dict(x[0]); 变体.append(x)
            for i,数据 in enumerate(变体):
                p=根/f"bad-{i}.json"; p.write_text(json.dumps(数据),encoding="utf-8")
                with self.subTest(i=i),self.assertRaises(ValueError): 被测模块.安装已批准(p,清单,根,根/"repo")

    def test_install拒绝顺序错texture错和相对路径穿越且正式图不变(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根=Path(临时目录); 清单,审查,_=self._准备安装(根)
            变体=[]
            x=json.loads(json.dumps(审查)); x[0],x[1]=x[1],x[0]; 变体.append(x)
            x=json.loads(json.dumps(审查)); x[0]["texture"]=清单[1]["texture"]; 变体.append(x)
            x=json.loads(json.dumps(审查)); x[0]["candidate_path"]="../"+x[0]["candidate_path"]; 变体.append(x)
            for i,数据 in enumerate(变体):
                p=根/f"identity-bad-{i}.json"; p.write_text(json.dumps(数据),encoding="utf-8")
                with self.subTest(i=i),self.assertRaises(ValueError): 被测模块.安装已批准(p,清单,根,根/"repo")
                for n,项 in enumerate(清单): self.assertEqual(f"old-{n}".encode(),(根/"repo"/项["texture"]).read_bytes())

    def test_install成功五十九项且正式路径只来自清单(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根=Path(临时目录); 清单,审查,p=self._准备安装(根)
            备份=被测模块.安装已批准(p,清单,根,根/"repo")
            备份清单=json.loads((备份/"manifest.json").read_text(encoding="utf-8")); 备份PNG=list(备份.rglob("*.png"))
            self.assertEqual(59,len(备份PNG)); self.assertEqual(59,备份清单["count"]); self.assertEqual(59,len(备份清单["files"]))
            for 项,记录 in zip(清单,备份清单["files"]):
                备份图=备份/项["texture"]
                self.assertEqual(项["id"],记录["id"]); self.assertEqual(项["texture"],记录["texture"])
                self.assertEqual(hashlib.sha256(备份图.read_bytes()).hexdigest(),记录["old_sha256"])
                self.assertEqual(hashlib.sha256((根/"candidates"/Path(项["texture"]).name).read_bytes()).hexdigest(),记录["candidate_sha256"])
            for i,项 in enumerate(清单): self.assertEqual(f"candidate-{i}".encode(),(根/"repo"/项["texture"]).read_bytes())

    def test_install第N次replace失败则全量回滚(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根=Path(临时目录); 清单,审查,p=self._准备安装(根); 次数=[0]
            def 失败替换(src,dst):
                次数[0]+=1
                if 次数[0]==10: raise OSError("replace failed")
                os.replace(src,dst)
            with self.assertRaises(OSError): 被测模块.安装已批准(p,清单,根,根/"repo",替换器=失败替换)
            for i,项 in enumerate(清单): self.assertEqual(f"old-{i}".encode(),(根/"repo"/项["texture"]).read_bytes())
    def test_提示词包含逐项内容与全部硬约束(self):
        提示词 = 被测模块.构造提示词(示例项)
        for 片段 in (
            "Minecraft 物品栏图标", "单个完整物品", "正视或轻微3/4视角", "硬边像素画",
            "有限色板", "纯#00FF00键色背景", "主体不含键色", "无场景", "地面", "阴影",
            "文字", "品牌", "包装", "水印", "人物手持", "盲盒", "盒盖接缝", "靛蓝", "广告背景",
        ):
            self.assertIn(片段, 提示词)

    def test_每个物品形成独立命令(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            假脚本 = Path(临时目录) / "fake.py"; 假脚本.write_text("# fake", encoding="utf-8")
            另一项 = dict(示例项, id="letter", texture="assets/x/letter.png", subject="信件")
            命令一 = 被测模块.构造命令(示例项, Path("sources/blind_box.png"), 假脚本)
            命令二 = 被测模块.构造命令(另一项, Path("sources/letter.png"), 假脚本)
        self.assertEqual("generate", 命令一[2])
        self.assertIn("gpt-image-2", 命令一)
        self.assertIn("#00FF00", 命令一)
        self.assertIn(Path("sources/blind_box.png"), [Path(str(x)) for x in 命令一])
        self.assertNotEqual(命令一, 命令二)
        self.assertNotIn("信件", " ".join(map(str, 命令一)))

    def test_键色去背并输出十六像素二值透明边距(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            输入 = Path(临时目录) / "source.png"
            清理 = Path(临时目录) / "clean.png"
            候选 = Path(临时目录) / "candidate.png"
            图 = Image.new("RGB", (40, 20), (0, 255, 0))
            for x in range(5, 35):
                for y in range(2, 18):
                    图.putpixel((x, y), (90, 20, 180))
            图.save(输入)
            原始尺寸 = 被测模块.处理图像(输入, 清理, 候选)
            self.assertEqual((40, 20), 原始尺寸)
            with Image.open(候选) as 结果:
                self.assertEqual((16, 16), 结果.size)
                self.assertEqual("RGBA", 结果.mode)
                alpha = 结果.getchannel("A")
                self.assertLessEqual(alpha.getbbox()[2] - alpha.getbbox()[0], 14)
                self.assertLessEqual(alpha.getbbox()[3] - alpha.getbbox()[1], 14)
                self.assertGreaterEqual(alpha.getbbox()[0], 1)
                self.assertGreaterEqual(alpha.getbbox()[1], 1)
                self.assertEqual({0, 255}, set(alpha.get_flattened_data()))

    def test_resume仅在成功元数据与候选哈希匹配时跳过(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录)
            候选 = 根 / "candidates/blind_box.png"
            元数据 = 根 / "metadata/blind_box.json"
            候选.parent.mkdir(parents=True)
            元数据.parent.mkdir(parents=True)
            候选.write_bytes(b"candidate")
            哈希 = hashlib.sha256(候选.read_bytes()).hexdigest()
            元数据.write_text(json.dumps({"生成状态": "成功", "候选SHA256": 哈希}), encoding="utf-8")
            self.assertTrue(被测模块.可恢复跳过(元数据, 候选))
            候选.write_bytes(b"changed")
            self.assertFalse(被测模块.可恢复跳过(元数据, 候选))

    def test_非法选择被拒绝(self):
        with self.assertRaisesRegex(ValueError, "未找到"):
            被测模块.选择项目([示例项], ["missing"], False)

    def test_版本化输出优先使用本次新增文件(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            目录 = Path(临时目录)
            请求 = 目录 / "blind_box.png"
            请求.write_bytes(b"old")
            生成前 = {请求}
            新文件 = 目录 / "blind_box-2.png"
            新文件.write_bytes(b"new")
            self.assertEqual(新文件, 被测模块._实际源文件(目录, 请求, 生成前))

    def test_子进程失败不生成候选并写失败元数据(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录)
            假脚本 = 根 / "fake.py"; 假脚本.write_text("# fake", encoding="utf-8")
            def 失败运行器(*args, **kwargs):
                return subprocess.CompletedProcess(args[0], 7, "", "provider rejected secret text")
            状态 = 被测模块.生成单项(示例项, 根, 失败运行器, 图像脚本=假脚本)
            self.assertEqual("失败", 状态)
            self.assertFalse((根 / "candidates/blind_box.png").exists())
            失败文件 = list((根 / "failures").glob("blind_box-*.json"))
            self.assertEqual(1, len(失败文件))
            数据 = json.loads(失败文件[0].read_text(encoding="utf-8"))
            self.assertEqual("失败", 数据["生成状态"])
            self.assertNotIn("provider rejected", json.dumps(数据, ensure_ascii=False))

    def test_清单拒绝路径穿越与不安全编号(self):
        for 修改 in (
            {"id": "../blind_box"},
            {"texture": "../../escape.png"},
            {"texture": "assets/blindboxchallenge/textures/item/../escape.png"},
        ):
            项 = dict(示例项, **修改)
            with self.subTest(修改=修改), self.assertRaisesRegex(ValueError, "清单"):
                被测模块.校验清单数据([项])

    def test_清单拒绝重复id与重复贴图名(self):
        第二项 = dict(示例项, id="letter", texture="assets/blindboxchallenge/textures/item/letter.png")
        for 重复项 in (dict(第二项, id="blind_box"), dict(第二项, texture=示例项["texture"])):
            with self.subTest(重复项=重复项), self.assertRaisesRegex(ValueError, "重复"):
                被测模块.校验清单数据([示例项, 重复项])

    def test_选择索引遇到重复键不会静默覆盖(self):
        冲突 = dict(示例项, id="other", texture="assets/blindboxchallenge/textures/item/blind_box.png")
        with self.assertRaisesRegex(ValueError, "重复"):
            被测模块.选择项目([示例项, 冲突], ["blind_box"], False)

    def test_输出路径不能逃离对应子目录(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            with self.assertRaisesRegex(ValueError, "输出路径"):
                被测模块.安全输出路径(Path(临时目录), "candidates", "../escape.png")

    def test_单项锁竞争时明确失败(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录)
            with 被测模块.单项锁(根, "blind_box"):
                with self.assertRaisesRegex(RuntimeError, "正在生成"):
                    with 被测模块.单项锁(根, "blind_box"):
                        pass

    def test_释放后可再次获取且持久锁文件不代表占用(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录)
            with 被测模块.单项锁(根, "blind_box"): pass
            锁 = 根 / ".locks/blind_box.lock"
            self.assertTrue(锁.exists())
            with 被测模块.单项锁(根, "blind_box"): pass

    def test_不同stem可同时进入(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            with 被测模块.单项锁(Path(临时目录), "blind_box"):
                with 被测模块.单项锁(Path(临时目录), "letter"):
                    pass

    def test_确定性交错下同一锁不会同时进入(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录); 已进入 = threading.Event(); 允许退出 = threading.Event(); 结果 = []
            def 持有者():
                with 被测模块.单项锁(根, "blind_box"):
                    结果.append("A进入"); 已进入.set(); 允许退出.wait(5)
            线程 = threading.Thread(target=持有者); 线程.start(); self.assertTrue(已进入.wait(5))
            try:
                with self.assertRaisesRegex(RuntimeError, "正在生成"):
                    with 被测模块.单项锁(根, "blind_box"): 结果.append("B进入")
            finally:
                允许退出.set(); 线程.join(5)
            self.assertEqual(["A进入"], 结果)

    def test_子进程强杀后操作系统自动释放锁(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录); 就绪 = 根 / "ready"
            代码 = (
                "import sys,time; from pathlib import Path; "
                "from tools.generate_item_texture_candidates import 单项锁; "
                "root=Path(sys.argv[1]); ready=Path(sys.argv[2]); "
                "ctx=单项锁(root,'blind_box'); ctx.__enter__(); ready.write_text('ok'); time.sleep(60)"
            )
            进程 = subprocess.Popen([sys.executable, "-c", 代码, str(根), str(就绪)], cwd=被测模块.仓库根目录)
            try:
                for _ in range(100):
                    if 就绪.exists(): break
                    threading.Event().wait(0.02)
                self.assertTrue(就绪.exists())
                with self.assertRaisesRegex(RuntimeError, "正在生成"):
                    with 被测模块.单项锁(根, "blind_box"): pass
                进程.kill(); 进程.wait(timeout=5)
                with 被测模块.单项锁(根, "blind_box"): pass
            finally:
                if 进程.poll() is None: 进程.kill(); 进程.wait(timeout=5)

    def test_同stem前缀旧文件不会污染唯一运行目录(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录)
            (根 / "sources").mkdir()
            (根 / "sources/blind_box_old.png").write_bytes(b"pollution")
            假脚本 = 根 / "fake.py"
            假脚本.write_text("# fake", encoding="utf-8")
            def 成功运行器(命令, **kwargs):
                请求 = Path(命令[命令.index("--output") + 1])
                图 = Image.new("RGB", (20, 20), (0, 255, 0))
                for x in range(4, 16):
                    for y in range(4, 16):
                        图.putpixel((x, y), (80, 40, 160))
                图.save(请求.with_name(requested_name := requested_stem(请求) + "-2.png"))
                return subprocess.CompletedProcess(命令, 0, "", "")
            def requested_stem(路径):
                return 路径.stem
            self.assertEqual("成功", 被测模块.生成单项(示例项, 根, 成功运行器, 图像脚本=假脚本))
            数据 = json.loads((根 / "metadata/blind_box.json").read_text(encoding="utf-8"))
            self.assertNotIn("blind_box_old.png", 数据["源文件"])

    def test_失败保留上一次成功提交并写独立失败记录(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            根 = Path(临时目录)
            for 相对, 内容 in (("candidates/blind_box.png", b"candidate"), ("clean/blind_box.png", b"clean")):
                路径 = 根 / 相对; 路径.parent.mkdir(parents=True, exist_ok=True); 路径.write_bytes(内容)
            元数据 = 根 / "metadata/blind_box.json"; 元数据.parent.mkdir(parents=True)
            元数据.write_text(json.dumps({"生成状态": "成功", "候选SHA256": hashlib.sha256(b"candidate").hexdigest()}), encoding="utf-8")
            假脚本 = 根 / "fake.py"; 假脚本.write_text("# fake", encoding="utf-8")
            def 失败运行器(*args, **kwargs):
                return subprocess.CompletedProcess(args[0], 9, "", "secret response")
            self.assertEqual("失败", 被测模块.生成单项(示例项, 根, 失败运行器, 图像脚本=假脚本))
            self.assertEqual(b"candidate", (根 / "candidates/blind_box.png").read_bytes())
            self.assertEqual(b"clean", (根 / "clean/blind_box.png").read_bytes())
            self.assertEqual("成功", json.loads(元数据.read_text(encoding="utf-8"))["生成状态"])
            self.assertTrue(list((根 / "failures").glob("blind_box-*.json")))

    def test_脚本发现支持显式参数环境变量与不存在错误(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            脚本 = Path(临时目录) / "fake.py"; 脚本.write_text("# fake", encoding="utf-8")
            self.assertEqual(脚本.resolve(), 被测模块.发现图像脚本(脚本, {}))
            self.assertEqual(脚本.resolve(), 被测模块.发现图像脚本(None, {"CHORDVERS_IMAGEGEN_SCRIPT": str(脚本)}))
            with self.assertRaisesRegex(ValueError, "未找到"):
                被测模块.发现图像脚本(Path(临时目录) / "missing.py", {})

    def test_隔离环境下显式假脚本不依赖本机路径(self):
        with tempfile.TemporaryDirectory() as 临时目录:
            假脚本 = Path(临时目录) / "fake.py"; 假脚本.write_text("# fake", encoding="utf-8")
            隔离环境 = {"USERPROFILE": str(Path(临时目录) / "missing-profile")}
            with patch.dict(os.environ, 隔离环境, clear=True):
                命令 = 被测模块.构造命令(示例项, Path(临时目录) / "out.png", 假脚本)
            self.assertEqual(str(假脚本.resolve()), 命令[1])

    def test_近绿色仅去除与画布边界连通区域(self):
        图 = Image.new("RGB", (9, 9), (3, 250, 4))
        for x in range(2, 7):
            for y in range(2, 7):
                图.putpixel((x, y), (100, 30, 150))
        图.putpixel((4, 4), (0, 240, 0))
        结果 = 被测模块._二值去背(图)
        self.assertEqual(0, 结果.getpixel((0, 0))[3])
        self.assertEqual(255, 结果.getpixel((4, 4))[3])


if __name__ == "__main__":
    unittest.main()
