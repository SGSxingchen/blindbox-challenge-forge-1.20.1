"""直接运行生产下载器的有界正文、缓存和同址等待逻辑，不访问网络或伪装音频解码。"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HARNESS = r'''
import cn.blindboxchallenge.client.audio.RemoteAudioDownload;
import cn.blindboxchallenge.service.AudioDownloadLimits;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class DownloadRegression {
    static final Class<?> TYPE = RemoteAudioDownload.class;
    static final int MIB = 1024 * 1024;
    static final AudioDownloadLimits SMALL = new AudioDownloadLimits(MIB, 1000, 1000, 1000);
    static final AudioDownloadLimits LARGE = new AudioDownloadLimits(2*MIB, 1000, 1000, 3000);
    static Path cache;
    static int assertions;
    static Object call(String name, Object... args) throws Exception {
        Method method=Arrays.stream(TYPE.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        try { return method.invoke(null,args); }
        catch(InvocationTargetException e) { if(e.getCause() instanceof Exception ex) throw ex; throw e; }
    }
    static Object field(String name) throws Exception { Field f=TYPE.getDeclaredField(name);f.setAccessible(true);return f.get(null); }
    static void check(boolean ok,String msg) { assertions++;if(!ok)throw new AssertionError(msg); }
    static String hash(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    static long deadline(long millis) { return System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis); }
    static byte[] audio(int size) { byte[] bytes=new byte[size];bytes[0]='O';bytes[1]='g';bytes[2]='g';bytes[3]='S';return bytes; }
    static Object save(String url,byte[] data,int limit) throws Exception {
        return call("saveResponse",new ByteArrayInputStream(data),cache,hash(url),deadline(5000),(long)data.length,limit);
    }
    interface Action { void run() throws Exception; }
    static void rejects(Action action, RemoteAudioDownload.FailureStage expected) throws Exception {
        try { action.run();throw new AssertionError("应拒绝的下载被接受"); }
        catch(RemoteAudioDownload.AudioFailureException failure) {
            Throwable current=failure;boolean found=false;
            while(current!=null){if(current instanceof RemoteAudioDownload.AudioFailureException s && s.stage()==expected)found=true;current=current.getCause();}
            check(found,"失败阶段应为"+expected);
        }
        try(var files=Files.list(cache)){check(files.noneMatch(p -> p.toString().endsWith(".part")),"失败后残留临时文件");}
    }
    static class SlowInput extends ByteArrayInputStream {
        SlowInput(){super(audio(8));}
        @Override public synchronized int read(byte[] out,int offset,int length){
            try {Thread.sleep(160);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            return super.read(out,offset,length);
        }
    }
    static class Waiting extends CompletableFuture<Object> {
        final CountDownLatch waiting=new CountDownLatch(1);
        @Override public Object get(long n,TimeUnit unit)throws InterruptedException,ExecutionException,TimeoutException {waiting.countDown();return super.get(n,unit);}
    }
    @SuppressWarnings("unchecked")
    static void follower(String url, AudioDownloadLimits limits, boolean shouldFail) throws Exception {
        var flights=(ConcurrentHashMap<String,CompletableFuture<Object>>)field("IN_FLIGHT");
        Waiting pending=new Waiting();flights.put(hash(url),pending);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        RemoteAudioDownload.CachedAudio owner=null;
        try {
            Future<RemoteAudioDownload.CachedAudio> result=executor.submit(() -> RemoteAudioDownload.fetch(url,limits));
            check(pending.waiting.await(2,TimeUnit.SECONDS),"等待者没有加入同址下载");
            Object stored=save(url,audio(MIB+1),2*MIB);
            owner=(RemoteAudioDownload.CachedAudio)call("leaseOrCancel",stored,LARGE);
            pending.complete(stored);
            if(shouldFail){
                try{result.get(3,TimeUnit.SECONDS);throw new AssertionError("同址等待绕过大小限制");}
                catch(ExecutionException e){check(e.getCause() instanceof RemoteAudioDownload.AudioFailureException s && s.stage()==RemoteAudioDownload.FailureStage.LIMIT,"等待者限制失败类型");}
            } else {
                try(var second=result.get(3,TimeUnit.SECONDS)){
                    check(second.singleFlightFollower() && second!=owner,"等待者没有独立租约");
                    owner.close();owner=null;
                    check(Files.isRegularFile(second.path()),"先关闭发起者破坏等待者文件");
                }
            }
        } finally { if(owner!=null)owner.close();flights.remove(hash(url),pending);executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS); }
        check(((Map<?,?>)field("CACHE_REFERENCES")).isEmpty(),"同址流程泄漏引用");
    }
    public static void main(String[] args) throws Exception {
        net.minecraft.client.Minecraft.getInstance().gameDirectory=new File(args[0]);
        cache=Path.of(args[0],"blindboxchallenge-audio-cache");Files.createDirectories(cache);
        check(AudioDownloadLimits.DEFAULTS.totalTimeoutMillis()==60_000,"默认总时限不是60秒");
        int[][] invalid={{0,1000,1000,1000},{17*MIB,1000,1000,1000},{MIB,0,1000,1000},{MIB,61000,1000,1000},{MIB,1000,0,1000},{MIB,1000,61000,1000},{MIB,1000,1000,0},{MIB,1000,1000,120001}};
        for(int[] x:invalid){try{new AudioDownloadLimits(x[0],x[1],x[2],x[3]);throw new AssertionError("越界限制被接受");}catch(IllegalArgumentException expected){assertions++;}}
        rejects(() -> call("saveResponse",new InputStream(){public int read(){throw new AssertionError("已知长度超限仍读取正文");}},cache,hash("known"),deadline(1000),(long)MIB+1,MIB),RemoteAudioDownload.FailureStage.LIMIT);
        rejects(() -> call("saveResponse",new ByteArrayInputStream(audio(MIB+1)),cache,hash("unknown"),deadline(1000),-1L,MIB),RemoteAudioDownload.FailureStage.LIMIT);
        rejects(() -> call("saveResponse",new ByteArrayInputStream(audio(8)),cache,hash("short"),deadline(1000),100L,MIB),RemoteAudioDownload.FailureStage.BODY);
        rejects(() -> call("saveResponse",new SlowInput(),cache,hash("slow-short"),deadline(50),8L,MIB),RemoteAudioDownload.FailureStage.TOTAL_TIMEOUT);
        Object stored=call("saveResponse",new SlowInput(),cache,hash("slow-long"),deadline(2000),8L,MIB);
        try(var audio=(RemoteAudioDownload.CachedAudio)call("leaseOrCancel",stored,LARGE)){check(Files.size(audio.path())==8,"较长预算仍失败");}
        String url="https://example.com/large.ogg";
        stored=save(url,audio(MIB+1),2*MIB);Path cached;
        try(var audio=(RemoteAudioDownload.CachedAudio)call("leaseOrCancel",stored,LARGE)){cached=audio.path();}
        rejects(() -> RemoteAudioDownload.fetch(url,SMALL),RemoteAudioDownload.FailureStage.LIMIT);
        check(Files.isRegularFile(cached),"较严格服务器错误删除其它服务器的有效缓存");
        check(((Map<?,?>)field("CACHE_REFERENCES")).isEmpty(),"拒绝旧缓存泄漏预留");
        try(var audio=RemoteAudioDownload.fetch(url,LARGE)){check(audio.cacheHit(),"有效旧缓存不能命中");}
        follower("https://example.com/follower-small.ogg",SMALL,true);
        follower("https://example.com/follower-valid.ogg",LARGE,false);
        var flights=(ConcurrentHashMap<String,CompletableFuture<Object>>)field("IN_FLIGHT");
        Waiting never=new Waiting();String timeoutUrl="https://example.com/follower-timeout.ogg";flights.put(hash(timeoutUrl),never);
        try{rejects(() -> RemoteAudioDownload.fetch(timeoutUrl,SMALL),RemoteAudioDownload.FailureStage.TOTAL_TIMEOUT);check(!never.isCancelled(),"等待者超时取消了别人的下载");}finally{flights.remove(hash(timeoutUrl));}
        Path activePart=cache.resolve("active.part");Files.write(activePart,new byte[1]);Files.setLastModifiedTime(activePart,FileTime.fromMillis(System.currentTimeMillis()-90_000));
        call("cleanupStaleParts",cache);check(Files.exists(activePart),"仍在120秒预算内的临时文件被删除");Files.delete(activePart);
        check(((Map<?,?>)field("CACHE_REFERENCES")).isEmpty(),"最终仍有缓存引用");
        System.out.println("下载限制回归断言="+assertions);
    }
}
'''


class 下载限制回归(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "需要Java开发工具")
    def test_预算大小缓存与同址等待(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            stub=root/'net/minecraft/client/Minecraft.java'
            stub.parent.mkdir(parents=True)
            stub.write_text('package net.minecraft.client; public class Minecraft { private static final Minecraft INSTANCE=new Minecraft(); public java.io.File gameDirectory; public static Minecraft getInstance(){return INSTANCE;} }')
            harness=root/'DownloadRegression.java';harness.write_text(HARNESS,encoding='utf-8')
            sources=[ROOT/'mod/src/main/java'/name for name in (
                'cn/blindboxchallenge/service/AudioUrlPolicy.java',
                'cn/blindboxchallenge/service/AudioDownloadLimits.java',
                'cn/blindboxchallenge/client/audio/RemoteAudioDownload.java')]
            subprocess.run(['javac','--release','17','-encoding','UTF-8','-d',directory,*map(str,sources),str(stub),str(harness)],check=True,capture_output=True,text=True,timeout=30)
            result=subprocess.run(['java','-cp',directory,'DownloadRegression',directory],check=True,capture_output=True,text=True,timeout=15)
            self.assertIn('下载限制回归断言=',result.stdout)
