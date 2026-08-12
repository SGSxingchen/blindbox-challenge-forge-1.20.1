#!/usr/bin/env python3
"""逐项生成并整理物品贴图候选；不会修改正式资源。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import uuid
from collections import deque
from contextlib import ExitStack, contextmanager
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Callable, Iterator, Mapping

from PIL import Image

if os.name == "nt":
    import msvcrt
else:
    import fcntl

仓库根目录 = Path(__file__).resolve().parents[1]
默认清单 = 仓库根目录 / "tools/item_texture_redraw_manifest.json"
默认输出根 = 仓库根目录 / "output/imagegen/item-redraw"
默认正式根 = 仓库根目录 / "mod/src/main/resources"
安全名规则 = re.compile(r"^[a-z0-9_]+$")
必需字段 = {"id", "texture", "subject", "must_show", "palette", "avoid"}


def 构造提示词(项: dict, 后缀: str = "") -> str:
    标准 = (
        f"制作一个 Minecraft 物品栏图标：{项['subject']}。只画单个完整物品，正视或轻微3/4视角；"
        f"必须清楚表现：{项['must_show']}。采用硬边像素画、无抗锯齿、有限色板，"
        f"配色仅围绕：{'、'.join(项['palette'])}。画布使用纯#00FF00键色背景，主体不含键色。"
        "无场景、无地面、无阴影、无文字、无品牌、无包装、无水印、无人物手持。"
        f"逐项额外禁用：{'、'.join(项['avoid'])}。不要合并或补充其他物品。"
    )
    return 标准 + (后缀.strip() if 后缀 else "")


def 发现图像脚本(显式路径: Path | None, 环境: Mapping[str, str] = os.environ) -> Path:
    if 显式路径 is not None:
        候选 = [Path(显式路径)]
    elif 环境.get("CHORDVERS_IMAGEGEN_SCRIPT"):
        候选 = [Path(环境["CHORDVERS_IMAGEGEN_SCRIPT"])]
    else:
        候选 = []
        if 环境.get("CODEX_HOME"):
            候选.append(Path(环境["CODEX_HOME"]) / "skills/chordvers-imagegen/scripts/chordvers_imagegen.py")
        if 环境.get("USERPROFILE"):
            候选.append(Path(环境["USERPROFILE"]) / ".codex/skills/chordvers-imagegen/scripts/chordvers_imagegen.py")
        候选.append(Path.home() / ".codex/skills/chordvers-imagegen/scripts/chordvers_imagegen.py")
    for 路径 in 候选:
        if 路径.is_file():
            return 路径.resolve()
    raise ValueError("未找到 chordvers 图像生成脚本，请使用 --imagegen-script 或设置 CHORDVERS_IMAGEGEN_SCRIPT")


def 构造命令(项: dict, 请求输出: Path, 图像脚本: Path | None = None, 提示词后缀: str = "") -> list[str]:
    脚本 = 发现图像脚本(图像脚本)
    return [sys.executable, str(脚本), "generate", "--model", "gpt-image-2", "--prompt", 构造提示词(项, 提示词后缀),
            "--chroma-key", "#00FF00", "--output", str(请求输出)]


def 校验清单数据(数据: object) -> list[dict]:
    if not isinstance(数据, list):
        raise ValueError("清单顶层必须是数组")
    编号集: set[str] = set(); stem集: set[str] = set(); 路径集: set[str] = set()
    结果: list[dict] = []
    for 序号, 项 in enumerate(数据, 1):
        if not isinstance(项, dict):
            raise ValueError(f"清单第 {序号} 项必须是对象")
        缺失 = 必需字段 - 项.keys()
        if 缺失:
            raise ValueError(f"清单第 {序号} 项缺少字段：{'、'.join(sorted(缺失))}")
        for 字段 in ("id", "texture", "subject", "must_show"):
            if not isinstance(项[字段], str) or not 项[字段].strip():
                raise ValueError(f"清单第 {序号} 项 {字段} 必须是非空字符串")
        if not 安全名规则.fullmatch(项["id"]):
            raise ValueError(f"清单第 {序号} 项 id 不是安全小写编号")
        路径文本 = 项["texture"]
        纯路径 = PurePosixPath(路径文本)
        部件 = 纯路径.parts
        if "\\" in 路径文本 or 纯路径.is_absolute() or "." in 部件 or ".." in 部件 or len(部件) != 5 or 部件[0] != "assets" or 部件[2:4] != ("textures", "item") or 纯路径.suffix != ".png":
            raise ValueError(f"清单第 {序号} 项 texture 不是安全物品贴图路径")
        stem = 纯路径.stem
        if not 安全名规则.fullmatch(stem):
            raise ValueError(f"清单第 {序号} 项 texture 文件名不安全")
        for 字段 in ("palette", "avoid"):
            if not isinstance(项[字段], list) or not 项[字段] or not all(isinstance(x, str) and x.strip() for x in 项[字段]):
                raise ValueError(f"清单第 {序号} 项 {字段} 必须是非空字符串数组")
        if 项["id"] in 编号集: raise ValueError(f"清单存在重复 id：{项['id']}")
        if stem in stem集: raise ValueError(f"清单存在重复 texture stem：{stem}")
        if 路径文本 in 路径集: raise ValueError(f"清单存在重复 texture：{路径文本}")
        编号集.add(项["id"]); stem集.add(stem); 路径集.add(路径文本); 结果.append(项)
    冲突 = (编号集 & stem集) - {项["id"] for 项 in 结果 if 项["id"] == PurePosixPath(项["texture"]).stem}
    if 冲突:
        raise ValueError("清单选择别名重复：" + "、".join(sorted(冲突)))
    return 结果


def 选择项目(清单: list[dict], 选择: list[str], 全部: bool) -> list[dict]:
    索引: dict[str, dict] = {}
    for 项 in 清单:
        for 键 in (项["id"], PurePosixPath(项["texture"]).stem):
            if 键 in 索引 and 索引[键] is not 项:
                raise ValueError(f"清单选择键重复：{键}")
            索引[键] = 项
    if 全部: return list(清单)
    未找到 = [值 for 值 in 选择 if 值 not in 索引]
    if 未找到: raise ValueError("未找到选择项：" + "、".join(未找到))
    结果=[]; 已有=set()
    for 值 in 选择:
        项=索引[值]
        if 项["id"] not in 已有: 结果.append(项); 已有.add(项["id"])
    return 结果


def 安全输出路径(输出根: Path, 子目录: str, 文件名: str) -> Path:
    根 = (Path(输出根).resolve() / 子目录).resolve()
    目标 = (根 / 文件名).resolve()
    if 目标.parent != 根:
        raise ValueError("输出路径超出允许的子目录")
    return 目标


def 文件哈希(路径: Path) -> str: return hashlib.sha256(路径.read_bytes()).hexdigest()


def 可恢复跳过(元数据路径: Path, 候选路径: Path) -> bool:
    try:
        数据=json.loads(元数据路径.read_text(encoding="utf-8"))
        return 数据.get("生成状态")=="成功" and 文件哈希(候选路径)==数据.get("候选SHA256")
    except (OSError, ValueError, json.JSONDecodeError): return False


@contextmanager
def 单项锁(输出根: Path, 安全名: str) -> Iterator[None]:
    if 安全名!="install-all" and not 安全名规则.fullmatch(安全名): raise ValueError("锁名称不安全")
    锁目录=(Path(输出根).resolve()/".locks"); 锁目录.mkdir(parents=True, exist_ok=True)
    锁路径=锁目录/f"{安全名}.lock"
    审计路径=锁目录/f"{安全名}.owner.json"; token=uuid.uuid4().hex
    句柄=open(锁路径,"a+b")
    try:
        句柄.seek(0,os.SEEK_END)
        if 句柄.tell()==0:
            句柄.write(b"\0"); 句柄.flush(); os.fsync(句柄.fileno())
        句柄.seek(0)
        try:
            if os.name=="nt": msvcrt.locking(句柄.fileno(),msvcrt.LK_NBLCK,1)
            else: fcntl.flock(句柄.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
        except OSError as 异常:
            raise RuntimeError(f"{安全名} 正在生成或安装，无法重复执行") from 异常
        _原子JSON(审计路径,{"pid":os.getpid(),"created_at":datetime.now(timezone.utc).isoformat(),"token":token})
        yield
    finally:
        try:
            if 审计路径.exists():
                try:
                    if json.loads(审计路径.read_text(encoding="utf-8")).get("token")==token: 审计路径.unlink(missing_ok=True)
                except (OSError,json.JSONDecodeError): pass
            句柄.seek(0)
            if os.name=="nt": msvcrt.locking(句柄.fileno(),msvcrt.LK_UNLCK,1)
            else: fcntl.flock(句柄.fileno(),fcntl.LOCK_UN)
        except OSError:
            pass
        finally:
            句柄.close()


def _二值去背(图像: Image.Image) -> Image.Image:
    rgba=图像.convert("RGBA"); 宽,高=rgba.size
    def 是键色(x:int,y:int)->bool:
        r,g,b,a=rgba.getpixel((x,y)); return a<128 or (g>=190 and r<=80 and b<=80 and g-max(r,b)>=100)
    队列=deque(); 已访问=set()
    for x in range(宽):
        for y in (0,高-1):
            if 是键色(x,y): 队列.append((x,y)); 已访问.add((x,y))
    for y in range(高):
        for x in (0,宽-1):
            if 是键色(x,y): 队列.append((x,y)); 已访问.add((x,y))
    while 队列:
        x,y=队列.popleft()
        for nx,ny in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
            if 0<=nx<宽 and 0<=ny<高 and (nx,ny) not in 已访问 and 是键色(nx,ny):
                已访问.add((nx,ny)); 队列.append((nx,ny))
    输出=Image.new("RGBA",rgba.size); 像素=[]
    for y in range(高):
        for x in range(宽):
            r,g,b,a=rgba.getpixel((x,y)); 像素.append((r,g,b,0 if (x,y) in 已访问 or a<128 else 255))
    输出.putdata(像素); return 输出


def 处理图像(源路径: Path, 清理路径: Path, 候选路径: Path) -> tuple[int,int]:
    with Image.open(源路径) as im: im.load(); 尺寸=im.size; 透明图=_二值去背(im)
    边界=透明图.getchannel("A").getbbox()
    if 边界 is None: raise ValueError("去除键色后没有可见主体")
    裁切=透明图.crop(边界); 清理路径.parent.mkdir(parents=True,exist_ok=True); 裁切.save(清理路径,format="PNG")
    比例=min(14/裁切.width,14/裁切.height); 新尺寸=(max(1,round(裁切.width*比例)),max(1,round(裁切.height*比例)))
    缩放=裁切.resize(新尺寸,Image.Resampling.NEAREST); 画布=Image.new("RGBA",(16,16),(0,0,0,0))
    画布.alpha_composite(缩放,((16-新尺寸[0])//2,(16-新尺寸[1])//2)); 候选路径.parent.mkdir(parents=True,exist_ok=True); 画布.save(候选路径,format="PNG")
    return 尺寸


def _实际源文件(目录: Path, 请求输出: Path, 生成前: set[Path] | None = None) -> Path:
    文件=[p for p in 目录.glob(f"{请求输出.stem}*.png") if p.is_file()]
    if 生成前 is not None: 文件=[p for p in 文件 if p not in 生成前]
    if len(文件)!=1: raise FileNotFoundError(f"生图命令成功，但唯一运行目录内实际输出 PNG 数量为 {len(文件)}")
    return 文件[0]


def _原子字节写入(目标: Path, 数据: bytes) -> None:
    目标.parent.mkdir(parents=True,exist_ok=True)
    fd,临时名=tempfile.mkstemp(prefix=f".{目标.name}.",suffix=".tmp",dir=目标.parent)
    try:
        with os.fdopen(fd,"wb") as f: f.write(数据); f.flush(); os.fsync(f.fileno())
        os.replace(临时名,目标)
    except Exception:
        try: os.close(fd)
        except OSError: pass
        Path(临时名).unlink(missing_ok=True); raise


def _原子JSON(目标: Path, 数据: dict) -> None: _原子字节写入(目标,(json.dumps(数据,ensure_ascii=False,indent=2)+"\n").encode("utf-8"))


def _失败记录(输出根: Path, stem: str, run_id: str, 基础: dict, 原因: str) -> None:
    _原子JSON(安全输出路径(输出根,"failures",f"{stem}-{run_id}.json"),基础|{"生成状态":"失败","失败原因":原因})


def _utc版本名(哈希前缀: str) -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + 哈希前缀


def 归档现有版本(项: dict, 输出根: Path) -> Path | None:
    stem=PurePosixPath(项["texture"]).stem; 输出根=Path(输出根).resolve()
    文件={"source.png":安全输出路径(输出根,"sources",f"{项['id']}.png"),
          "clean.png":安全输出路径(输出根,"clean",f"{stem}.png"),
          "candidate.png":安全输出路径(输出根,"candidates",f"{stem}.png"),
          "metadata.json":安全输出路径(输出根,"metadata",f"{stem}.json")}
    存在=[p.exists() for p in 文件.values()]
    if not any(存在): return None
    if not all(存在): raise OSError("现有版本四层文件不完整，拒绝归档")
    版本根=输出根/"revisions"/stem; 版本根.mkdir(parents=True,exist_ok=True)
    目标=版本根/_utc版本名(文件哈希(文件["candidate.png"])[:8]); 临时=版本根/f".{目标.name}.{uuid.uuid4().hex}.tmp"
    try:
        临时.mkdir()
        for 名称,源 in 文件.items(): shutil.copy2(源,临时/名称)
        os.replace(临时,目标)
    except Exception:
        shutil.rmtree(临时,ignore_errors=True); raise
    return 目标


@contextmanager
def 安装事务锁(输出根: Path, 清单: list[dict]) -> Iterator[None]:
    stems=sorted(PurePosixPath(项["texture"]).stem for 项 in 清单)
    if len(stems)!=len(set(stems)): raise ValueError("清单存在重复候选锁名称")
    with ExitStack() as 栈:
        栈.enter_context(单项锁(输出根,"install-all"))
        for stem in stems: 栈.enter_context(单项锁(输出根,stem))
        yield


def _校验审查(审查路径: Path, 清单: list[dict], 输出根: Path) -> list[tuple[dict,Path,bytes,str]]:
    数据=json.loads(Path(审查路径).read_text(encoding="utf-8"))
    if not isinstance(数据,list) or len(数据)!=59 or len(清单)!=59: raise ValueError("审查与清单必须恰好包含59项")
    if len({x.get("id") for x in 数据 if isinstance(x,dict)})!=59: raise ValueError("审查存在重复项")
    结果=[]; 候选根=(Path(输出根).resolve()/"candidates").resolve()
    for 序号,(审查,项) in enumerate(zip(数据,清单),1):
        if not isinstance(审查,dict) or 审查.get("id")!=项["id"] or 审查.get("texture")!=项["texture"]: raise ValueError(f"审查第{序号}项顺序或身份不匹配")
        if 审查.get("status")!="pass": raise ValueError(f"审查第{序号}项尚未通过")
        候选文本=审查.get("candidate_path")
        if not isinstance(候选文本,str) or Path(候选文本).is_absolute() or PurePosixPath(候选文本).name!=候选文本 or "\\" in 候选文本: raise ValueError("候选路径必须是相对文件名")
        预期名=PurePosixPath(项["texture"]).name
        if 候选文本!=预期名: raise ValueError("候选路径与清单不匹配")
        候选=(候选根/候选文本).resolve()
        if 候选.parent!=候选根 or not 候选.is_file(): raise ValueError("候选文件或哈希不匹配")
        候选字节=候选.read_bytes(); 候选哈希=hashlib.sha256(候选字节).hexdigest()
        if 候选哈希!=审查.get("candidate_sha256"): raise ValueError("候选文件或哈希不匹配")
        结果.append((项,候选,候选字节,候选哈希))
    return 结果


class 安装回滚异常(RuntimeError):
    pass


def 安装已批准(审查路径: Path, 清单: list[dict], 输出根: Path, 仓库根: Path=默认正式根,
              替换器: Callable[[str|Path,str|Path],None]=os.replace,
              恢复器: Callable[[Path,bytes],None]=_原子字节写入) -> Path:
    输出根=Path(输出根).resolve(); 仓库根=Path(仓库根).resolve()
    with 安装事务锁(输出根,清单):
        已校验=_校验审查(审查路径,清单,输出根)
        staging=输出根/".install-staging"/uuid.uuid4().hex; staging.mkdir(parents=True)
        try:
            staging项=[]
            for 项,_,候选字节,候选哈希 in 已校验:
                快照=staging/PurePosixPath(项["texture"]).name; _原子字节写入(快照,候选字节)
                if 文件哈希(快照)!=候选哈希: raise OSError(f"候选 staging 哈希不匹配：{项['texture']}")
                staging项.append((项,快照,候选哈希))
            时间=datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")+"-"+uuid.uuid4().hex
            备份=输出根/"install-backup"/时间
            临时备份=备份.with_name(f".{备份.name}.{uuid.uuid4().hex}.tmp"); 临时备份.mkdir(parents=True)
            正式=[]; 清单哈希=[]
            try:
                for 项,快照,候选哈希 in staging项:
                    目标=(仓库根/项["texture"]).resolve()
                    if 仓库根 not in 目标.parents: raise ValueError("正式路径超出仓库")
                    if not 目标.is_file(): raise ValueError(f"正式贴图不存在：{项['texture']}")
                    相对=PurePosixPath(项["texture"]); 备份文件=临时备份/相对
                    备份文件.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(目标,备份文件)
                    清单哈希.append({"id":项["id"],"texture":项["texture"],"old_sha256":文件哈希(目标),
                                     "candidate_sha256":候选哈希})
                    正式.append((目标,快照,相对))
                _原子JSON(临时备份/"manifest.json",{"count":59,"files":清单哈希})
                os.replace(临时备份,备份)
            except Exception:
                shutil.rmtree(临时备份,ignore_errors=True); raise
            try:
                for 目标,快照,_ in 正式:
                    fd,临时名=tempfile.mkstemp(prefix=f".{目标.name}.",suffix=".tmp",dir=目标.parent); os.close(fd)
                    临时=Path(临时名)
                    try: shutil.copy2(快照,临时); 替换器(临时,目标)
                    finally: 临时.unlink(missing_ok=True)
            except Exception as 原始异常:
                回滚失败=[]
                for 目标,_,相对 in 正式:
                    try: 恢复器(目标,(备份/相对).read_bytes())
                    except Exception as 回滚异常: 回滚失败.append(f"{目标}: {回滚异常}")
                if 回滚失败:
                    raise 安装回滚异常(f"安装失败：{原始异常}；回滚失败："+"；".join(回滚失败)) from 原始异常
                raise
            return 备份
        finally:
            shutil.rmtree(staging,ignore_errors=True)
            staging父=staging.parent
            try: staging父.rmdir()
            except OSError: pass


def 生成单项(项: dict, 输出根: Path, 运行器: Callable=subprocess.run, 演练: bool=False, 图像脚本: Path|None=None,
           提示词后缀: str="", 归档已有: bool=False) -> str:
    stem=PurePosixPath(项["texture"]).stem; 输出根=Path(输出根).resolve(); run_id=uuid.uuid4().hex
    脚本=发现图像脚本(图像脚本); run_dir=输出根/".runs"/f"{stem}-{run_id}"; 请求源=run_dir/f"{项['id']}.png"
    命令=构造命令(项,请求源,脚本,提示词后缀); 基础={"资源id":项["id"],"texture":项["texture"],"最终提示词":构造提示词(项,提示词后缀),"提示词后缀":提示词后缀,"命令":命令,"时间":datetime.now(timezone.utc).isoformat()}
    if 演练: print(json.dumps(基础,ensure_ascii=False)); return "演练"
    try:
        with 单项锁(输出根,stem):
            if 归档已有: 归档现有版本(项,输出根)
            run_dir.mkdir(parents=True,exist_ok=False)
            结果=运行器(命令,text=True,capture_output=True,encoding="utf-8",errors="replace")
            if 结果.returncode!=0: raise RuntimeError(f"生图子进程失败（退出码 {结果.returncode}）")
            实际源=_实际源文件(run_dir,请求源)
            run_clean=run_dir/f"clean-{stem}.png"; run_candidate=run_dir/f"candidate-{stem}.png"; 尺寸=处理图像(实际源,run_clean,run_candidate)
            目标源=安全输出路径(输出根,"sources",f"{项['id']}.png"); 目标clean=安全输出路径(输出根,"clean",f"{stem}.png"); 目标候选=安全输出路径(输出根,"candidates",f"{stem}.png"); 目标元数据=安全输出路径(输出根,"metadata",f"{stem}.json")
            旧内容={p:(p.read_bytes() if p.exists() else None) for p in (目标源,目标clean,目标候选)}
            try:
                _原子字节写入(目标源,实际源.read_bytes()); _原子字节写入(目标clean,run_clean.read_bytes()); _原子字节写入(目标候选,run_candidate.read_bytes())
                _原子JSON(目标元数据,基础|{"源文件":str(目标源),"源文件实际尺寸":list(尺寸),"clean文件":str(目标clean),"候选文件":str(目标候选),"候选SHA256":文件哈希(目标候选),"生成状态":"成功"})
            except Exception:
                for p,内容 in 旧内容.items():
                    if 内容 is None: p.unlink(missing_ok=True)
                    else: _原子字节写入(p,内容)
                raise
            return "成功"
    except Exception as 异常:
        原因=str(异常) if isinstance(异常,(FileNotFoundError,ValueError,RuntimeError)) else type(异常).__name__
        _失败记录(输出根,stem,run_id,基础,原因); return "失败"
    finally:
        shutil.rmtree(run_dir,ignore_errors=True)


class _参数解析器(argparse.ArgumentParser):
    def parse_args(self, args=None, namespace=None):
        参数=super().parse_args(args,namespace)
        if 参数.install_approved and (参数.only or 参数.all or 参数.dry_run or 参数.resume or 参数.prompt_suffix is not None or 参数.archive_existing or 参数.imagegen_script):
            self.error("--install-approved 与生图模式参数互斥")
        模式数=bool(参数.only)+bool(参数.all)+bool(参数.install_approved)
        if 模式数!=1: self.error("--only、--all 与 --install-approved 三种模式互斥且必须选择一种")
        if 参数.prompt_suffix is not None and (not 参数.only or len(参数.only)!=1): self.error("--prompt-suffix 仅允许与恰好一个 --only 一起使用")
        if 参数.archive_existing and (not 参数.only or len(参数.only)!=1): self.error("--archive-existing 仅允许与恰好一个 --only 一起使用")
        return 参数


def 创建参数解析器() -> argparse.ArgumentParser:
    p=_参数解析器(description=__doc__)
    p.add_argument("--only",action="append",default=[],metavar="ID或贴图名"); p.add_argument("--all",action="store_true")
    p.add_argument("--install-approved",type=Path,metavar="REVIEW_JSON")
    p.add_argument("--prompt-suffix"); p.add_argument("--archive-existing",action="store_true")
    p.add_argument("--dry-run",action="store_true"); p.add_argument("--resume",action="store_true"); p.add_argument("--output-root",type=Path,default=默认输出根); p.add_argument("--imagegen-script",type=Path)
    return p


def main() -> int:
    p=创建参数解析器(); 参数=p.parse_args()
    try:
        清单=校验清单数据(json.loads(默认清单.read_text(encoding="utf-8")))
        if 参数.install_approved:
            备份=安装已批准(参数.install_approved,清单,参数.output_root); print(f"已安装59项；备份：{备份}"); return 0
        项目=选择项目(清单,参数.only,参数.all); 脚本=发现图像脚本(参数.imagegen_script)
    except (OSError,json.JSONDecodeError,ValueError) as e: p.error(str(e))
    失败=0
    for 项 in 项目:
        stem=PurePosixPath(项["texture"]).stem
        if 参数.resume and 可恢复跳过(安全输出路径(参数.output_root,"metadata",f"{stem}.json"),安全输出路径(参数.output_root,"candidates",f"{stem}.png")):
            print(f"跳过（已验证哈希）：{项['id']}"); continue
        状态=生成单项(项,参数.output_root,演练=参数.dry_run,图像脚本=脚本,提示词后缀=参数.prompt_suffix or "",归档已有=参数.archive_existing); print(f"{项['id']}：{状态}"); 失败+=状态=="失败"
    return 1 if 失败 else 0

if __name__=="__main__": raise SystemExit(main())
