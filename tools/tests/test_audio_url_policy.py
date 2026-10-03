"""直接编译生产 URL 规则，校验签名、转义与重复读写不改变地址。"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class 音频地址回归(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "需要 Java 开发工具")
    def test_转义签名与重复规范化(self):
        source = ROOT / "mod/src/main/java/cn/blindboxchallenge/service/AudioUrlPolicy.java"
        harness = '''
import cn.blindboxchallenge.service.AudioUrlPolicy;

public class AudioUrlRegression {
    public static void main(String[] args) {
        String[][] accepted = {
            {"https://EXAMPLE.com:443/music/a%20b.ogg?sig=a%2Fb%3D&token=%25",
             "https://example.com/music/a%20b.ogg?sig=a%2Fb%3D&token=%25"},
            {"https://example.com/a/../b%2Fc.mp3?q=%23%26%2B", "https://example.com/b%2Fc.mp3?q=%23%26%2B"},
            {"https://example.com/音乐.ogg?title=音频", "https://example.com/%E9%9F%B3%E4%B9%90.ogg?title=%E9%9F%B3%E9%A2%91"},
            {"https://example.com", "https://example.com/"}
        };
        for (String[] test : accepted) {
            String actual = AudioUrlPolicy.normalizeHttpsUrl(test[0]);
            if (!test[1].equals(actual)) throw new AssertionError(actual);
            for (int i = 0; i < 10; i++) {
                actual = AudioUrlPolicy.normalizeHttpsUrl(actual);
                if (!test[1].equals(actual)) throw new AssertionError("重复规范化改变地址: " + actual);
            }
        }
        String[] rejected = {
            "http://example.com/a.ogg", "https://localhost/a.ogg", "https://127.0.0.1/a.ogg",
            "https://[::1]/a.ogg", "https://user:pass@example.com/a.ogg",
            "https://example.com:8443/a.ogg", "https://example.com/a.ogg#fragment",
            "https://example.com/a%ZZ.ogg"
        };
        for (String test : rejected) {
            try {
                AudioUrlPolicy.normalizeHttpsUrl(test);
            } catch (IllegalArgumentException expected) {
                continue;
            }
            throw new AssertionError("危险地址未拒绝: " + test);
        }
    }
}
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            probe = root / "AudioUrlRegression.java"
            probe.write_text(harness, encoding="utf-8")
            subprocess.run(
                ["javac", "--release", "17", "-encoding", "UTF-8", "-d", directory, str(source), str(probe)],
                check=True, capture_output=True, text=True, timeout=30,
            )
            subprocess.run(
                ["java", "-cp", directory, "AudioUrlRegression"],
                check=True, capture_output=True, text=True, timeout=15,
            )
