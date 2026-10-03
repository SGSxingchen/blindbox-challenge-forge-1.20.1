"""以纯 Java 接口桩运行生产音频服务，验证槽位回收与跨线程流关闭；不启动游戏。"""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


STUBS = {
    "cn/blindboxchallenge/BlindBoxChallenge.java": '''package cn.blindboxchallenge;
public final class BlindBoxChallenge { public static final String MOD_ID = "test"; }''',
    "com/mojang/logging/LogUtils.java": '''package com.mojang.logging;
public final class LogUtils { public static org.slf4j.Logger getLogger() { return new org.slf4j.Logger(); } }''',
    "org/slf4j/Logger.java": '''package org.slf4j;
public final class Logger { public void warn(String message, Object value) {} }''',
    "net/minecraft/core/BlockPos.java": '''package net.minecraft.core;
public final class BlockPos { public BlockPos immutable() { return this; } }''',
    "net/minecraft/sounds/SoundSource.java": '''package net.minecraft.sounds;
public enum SoundSource { MASTER, RECORDS }''',
    "net/minecraft/network/chat/Component.java": '''package net.minecraft.network.chat;
public final class Component { public static Component translatable(String key) { return new Component(); } }''',
    "net/minecraft/client/resources/sounds/SoundInstance.java": '''package net.minecraft.client.resources.sounds;
public interface SoundInstance {}''',
    "net/minecraft/client/sounds/AudioStream.java": '''package net.minecraft.client.sounds;
public interface AudioStream extends java.io.Closeable {
    javax.sound.sampled.AudioFormat getFormat();
    java.nio.ByteBuffer read(int bytes) throws java.io.IOException;
}''',
    "com/mojang/blaze3d/audio/OggAudioStream.java": '''package com.mojang.blaze3d.audio;
public final class OggAudioStream implements net.minecraft.client.sounds.AudioStream {
    public OggAudioStream(java.io.InputStream input) {}
    public javax.sound.sampled.AudioFormat getFormat() { return new javax.sound.sampled.AudioFormat(8000, 16, 1, true, false); }
    public java.nio.ByteBuffer read(int bytes) { return java.nio.ByteBuffer.allocate(0); }
    public void close() {}
}''',
    "net/minecraftforge/api/distmarker/Dist.java": '''package net.minecraftforge.api.distmarker;
public enum Dist { CLIENT }''',
    "net/minecraftforge/fml/common/Mod.java": '''package net.minecraftforge.fml.common;
public final class Mod { public @interface EventBusSubscriber {
    String modid(); net.minecraftforge.api.distmarker.Dist[] value();
} }''',
    "net/minecraftforge/eventbus/api/SubscribeEvent.java": '''package net.minecraftforge.eventbus.api;
public @interface SubscribeEvent {}''',
    "net/minecraftforge/eventbus/api/Event.java": '''package net.minecraftforge.eventbus.api;
public class Event {}''',
    "net/minecraftforge/event/TickEvent.java": '''package net.minecraftforge.event;
public class TickEvent {
    public enum Phase { START, END }
    public static final class ClientTickEvent {
        public final Phase phase; public ClientTickEvent(Phase phase) { this.phase = phase; }
    }
}''',
    "net/minecraftforge/client/event/ClientPlayerNetworkEvent.java": '''package net.minecraftforge.client.event;
public class ClientPlayerNetworkEvent { public static final class LoggingOut {} }''',
    "net/minecraftforge/client/event/sound/PlaySoundEvent.java": '''package net.minecraftforge.client.event.sound;
import net.minecraft.client.resources.sounds.SoundInstance;
public final class PlaySoundEvent {
    private final SoundInstance original; private SoundInstance sound;
    public PlaySoundEvent(SoundInstance original) { this.original = original; this.sound = original; }
    public SoundInstance getOriginalSound() { return original; }
    public SoundInstance getSound() { return sound; }
    public void setSound(SoundInstance value) { sound = value; }
}''',
    "net/minecraftforge/common/MinecraftForge.java": '''package net.minecraftforge.common;
public final class MinecraftForge {
    public static final Bus EVENT_BUS = new Bus();
    public static final class Bus { public boolean post(Object event) { return false; } }
}''',
    "net/minecraft/client/Minecraft.java": '''package net.minecraft.client;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
public final class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();
    public Player player = new Player(); public Object level = new Object();
    public final Options options = new Options();
    public final SoundManager sounds = new SoundManager();
    public final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    public static Minecraft getInstance() { return INSTANCE; }
    public void execute(Runnable task) { tasks.add(task); }
    public SoundManager getSoundManager() { return sounds; }
    public void drain() { Runnable task; while ((task = tasks.poll()) != null) task.run(); }
    public static final class Player {
        public int messages; public void displayClientMessage(Component component, boolean actionBar) { messages++; }
    }
    public static final class Options {
        public float master = 1, records = 1;
        public float getSoundSourceVolume(SoundSource source) { return source == SoundSource.MASTER ? master : records; }
    }
}''',
    "net/minecraft/client/sounds/SoundManager.java": '''package net.minecraft.client.sounds;
import java.util.HashSet;
import java.util.Set;
import cn.blindboxchallenge.client.audio.ClientMusicService;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
public final class SoundManager {
    public enum Mode { PLAY, CANCEL, WRAP, THROW_WRAP }
    public final Set<SoundInstance> active = new HashSet<>();
    public Mode mode = Mode.PLAY; public int stops;
    public void play(SoundInstance sound) {
        PlaySoundEvent event = new PlaySoundEvent(sound);
        ClientMusicService.observeSound(event);
        // 故意在生产观察器之后替换，验证读取的是事件派发结束后的最终声音。
        if (mode == Mode.CANCEL) event.setSound(null);
        if (mode == Mode.WRAP || mode == Mode.THROW_WRAP) event.setSound(new SoundInstance() {});
        if (event.getSound() != null) active.add(event.getSound());
        if (mode == Mode.THROW_WRAP) throw new IllegalStateException("播放失败");
    }
    public boolean isActive(SoundInstance sound) { return active.contains(sound); }
    public void stop(SoundInstance sound) { active.remove(sound); stops++; }
}''',
    "cn/blindboxchallenge/client/audio/RemoteAudioDownload.java": '''package cn.blindboxchallenge.client.audio;
public final class RemoteAudioDownload {
    public static final java.util.concurrent.atomic.AtomicInteger FETCHES = new java.util.concurrent.atomic.AtomicInteger();
    public static Object fetch(String url) { FETCHES.incrementAndGet(); return new Object(); }
    public enum FailureStage { UNKNOWN }
    public static final class AudioFailureException extends RuntimeException {
        public FailureStage stage() { return FailureStage.UNKNOWN; }
        public String connectionAttemptSummary() { return ""; }
    }
}''',
    "cn/blindboxchallenge/client/audio/RemoteMusicSoundInstance.java": '''package cn.blindboxchallenge.client.audio;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
public final class RemoteMusicSoundInstance implements SoundInstance {
    public static final CopyOnWriteArrayList<RemoteMusicSoundInstance> PREPARED = new CopyOnWriteArrayList<>();
    private final Runnable callback; public final AtomicBoolean closed = new AtomicBoolean();
    private RemoteMusicSoundInstance(Runnable callback) { this.callback = callback; }
    public static RemoteMusicSoundInstance prepare(Object audio, BlockPos pos, UUID id, Runnable callback) {
        var sound = new RemoteMusicSoundInstance(callback); PREPARED.add(sound); return sound;
    }
    public void discard() { if (closed.compareAndSet(false, true)) callback.run(); }
}''',
}


HARNESS = '''package cn.blindboxchallenge.client.audio;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import cn.blindboxchallenge.event.MusicBoxPlaybackEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;

public final class LifecycleRegression {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final TickEvent.ClientTickEvent END = new TickEvent.ClientTickEvent(TickEvent.Phase.END);
    private static Semaphore slots;
    private static Map<?, ?> playing;

    public static void main(String[] args) throws Exception {
        slots = (Semaphore) field("REMOTE_AUDIO_SLOTS").get(null);
        playing = (Map<?, ?>) field("PLAYING").get(null);
        request(); request(); awaitTasks(2); MC.drain();
        check(slots.availablePermits() == 0 && playing.size() == 2, "两条活跃音频应占用两个槽位");
        for (int i = 0; i < 500; i++) ClientMusicService.tick(END);
        check(slots.availablePermits() == 0, "活跃音频不得因时间经过而提前释放");
        int downloads = RemoteAudioDownload.FETCHES.get(); request();
        check(RemoteAudioDownload.FETCHES.get() == downloads, "槽位占满不得积压第三次下载");
        // 原版流自然 close 后，即使引擎还有短期声音登记，也只释放一次。
        RemoteMusicSoundInstance.PREPARED.get(0).discard(); ClientMusicService.tick(END);
        check(slots.availablePermits() == 1 && playing.size() == 1, "自然关闭应清理登记");
        MC.sounds.active.clear(); ClientMusicService.tick(END);
        check(slots.availablePermits() == 2 && playing.isEmpty(), "引擎停止但没打开流也应释放");

        MC.sounds.mode = SoundManager.Mode.CANCEL;
        for (int i = 0; i < 4; i++) {
            request(); awaitTasks(1); MC.drain();
            check(slots.availablePermits() == 2 && playing.isEmpty(), "取消声音不得耗尽槽位");
        }
        MC.sounds.mode = SoundManager.Mode.WRAP;
        request(); awaitTasks(1); MC.drain();
        for (int i = 0; i < 100; i++) ClientMusicService.tick(END);
        check(slots.availablePermits() == 1 && playing.size() == 1, "包装声音活跃时必须继续占用");
        MC.sounds.active.clear(); ClientMusicService.tick(END);
        check(slots.availablePermits() == 2 && playing.isEmpty(), "资源重载应回收未关闭的 PCM");

        downloads = RemoteAudioDownload.FETCHES.get(); MC.options.master = 0;
        request(); check(RemoteAudioDownload.FETCHES.get() == downloads && slots.availablePermits() == 2,
                "主音量静音不应开始下载");
        MC.options.master = 1; MC.options.records = 0; request();
        check(RemoteAudioDownload.FETCHES.get() == downloads, "唱片静音不应开始下载");
        MC.options.records = 1; request(); awaitTasks(1); MC.options.records = 0; MC.drain();
        check(slots.availablePermits() == 2 && playing.isEmpty(), "下载结束前静音也应回收 PCM");
        MC.options.records = 1;

        request(); awaitTasks(1); MC.drain();
        int stops = MC.sounds.stops;
        ClientMusicService.loggingOut(new ClientPlayerNetworkEvent.LoggingOut());
        check(slots.availablePermits() == 2 && playing.isEmpty() && MC.sounds.stops == stops + 1,
                "断线应先停止实际包装声音，再释放槽位");
        request(); awaitTasks(1);
        ClientMusicService.loggingOut(new ClientPlayerNetworkEvent.LoggingOut()); MC.drain();
        check(slots.availablePermits() == 2 && playing.isEmpty(), "旧连接的异步结果不得进入新连接");

        MC.sounds.mode = SoundManager.Mode.THROW_WRAP; request(); awaitTasks(1); MC.drain();
        check(slots.availablePermits() == 2 && playing.isEmpty() && MC.sounds.active.isEmpty(), "播放异常也应停止并释放");
        testConcurrentStreamClose();
        System.out.println("客户端音频生命周期与跨线程关闭回归通过");
    }

    private static void request() {
        ClientMusicService.play(new MusicBoxPlaybackEvent(UUID.randomUUID(), "https://example.com/music.ogg", new BlockPos(), 0));
    }
    private static void awaitTasks(int count) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (MC.tasks.size() < count) {
            if (System.nanoTime() >= deadline) throw new AssertionError("异步音频任务超时");
            Thread.sleep(1);
        }
    }
    private static Field field(String name) throws Exception {
        Field field = ClientMusicService.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void testConcurrentStreamClose() throws Exception {
        var audio = new BufferedAudioStream(new javax.sound.sampled.AudioFormat(8000, 16, 1, true, false), new byte[32000]);
        AtomicInteger callbacks = new AtomicInteger(); audio.setCloseCallback(callbacks::incrementAndGet);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var work = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) {
                final boolean closes = i % 2 == 0;
                work.add(pool.submit(() -> {
                    for (int j = 0; j < 1000; j++) { if (closes) audio.close(); else audio.read(512); }
                }));
            }
            for (var future : work) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            check(callbacks.get() == 1 && !audio.read(512).hasRemaining(), "并发关闭必须幂等且读取安全");
        } finally { pool.shutdownNow(); }
    }
    private static void check(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
}
'''


class 客户端音频生命周期回归(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "需要 Java 开发工具")
    def test_活跃与停止取消静音重载断线(self):
        production = ROOT / "mod/src/main/java"
        sources = [production / name for name in (
            "cn/blindboxchallenge/client/audio/ClientMusicService.java",
            "cn/blindboxchallenge/client/audio/BufferedAudioStream.java",
            "cn/blindboxchallenge/service/AudioUrlPolicy.java",
            "cn/blindboxchallenge/event/MusicBoxPlaybackEvent.java",
            "cn/blindboxchallenge/event/MusicBoxPlaybackFailedEvent.java",
        )]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative, text in STUBS.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
                sources.append(path)
            probe = root / "LifecycleRegression.java"
            probe.write_text(HARNESS, encoding="utf-8")
            subprocess.run(
                ["javac", "--release", "17", "-encoding", "UTF-8", "-d", directory,
                 *(str(source) for source in sources), str(probe)],
                check=True, capture_output=True, text=True, timeout=30,
            )
            subprocess.run(
                ["java", "-cp", directory, "cn.blindboxchallenge.client.audio.LifecycleRegression"],
                check=True, capture_output=True, text=True, timeout=15,
            )
