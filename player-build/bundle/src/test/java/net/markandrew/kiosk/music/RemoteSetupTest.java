package net.markandrew.kiosk.music;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import me.jxl.kiosk.plugins.PluginHost;
import static org.junit.Assert.*;

public class RemoteSetupTest {
    private RemoteSetup setup;
    private final AtomicInteger saves=new AtomicInteger(),publishes=new AtomicInteger(),reads=new AtomicInteger();
    private final List<String> results=new CopyOnWriteArrayList<>();
    private volatile PluginHost.CommandCallback pending;
    private volatile boolean delay,oldHost,reject;
    private final CountDownLatch finished=new CountDownLatch(1),read=new CountDownLatch(1);
    @Before public void start(){
        PluginHost host=(PluginHost)java.lang.reflect.Proxy.newProxyInstance(PluginHost.class.getClassLoader(),new Class<?>[]{PluginHost.class},(proxy,method,args)->{
            switch(method.getName()){
                case "saveSettings":case "updateSettings":saves.incrementAndGet();break;
                case "setupResult":
                    if(oldHost)throw new NoSuchMethodError();results.add(args[1]+":"+args[2]);if(!"running".equals(args[1]))finished.countDown();return !reject;
                case "setSettingChoices":publishes.incrementAndGet();assertEquals("addon",args[0]);assertArrayEquals(new String[]{"test_music_assistant"},(String[])args[1]);break;
                case "haCall":
                    reads.incrementAndGet();assertEquals("get_panels",((Map<?,?>)args[0]).get("type"));pending=(PluginHost.CommandCallback)args[1];read.countDown();if(!delay)answer();break;
                default:throw new AssertionError("Unexpected host method "+method.getName());
            }return null;
        });
        setup=new RemoteSetup(host,()->true,HashMap::new,session->"");
    }
    private void answer(){Map<String,Object> panel=new HashMap<>();panel.put("component_name","app");panel.put("title","Music Assistant");panel.put("config",Collections.singletonMap("addon","test_music_assistant"));pending.onResult(true,Collections.singletonMap("music",panel),null);}
    private Map<String,Object> event(){Map<String,Object> p=new HashMap<>();p.put("run","run1");p.put("key","addon");return p;}
    @After public void stop(){setup.stop();assertEquals(0,saves.get());}
    @Test public void refreshPublishesChoicesWithoutSavingAnything()throws Exception{
        assertTrue(setup.event("settings.choices",event()));assertTrue(finished.await(5,TimeUnit.SECONDS));assertEquals(1,publishes.get());assertTrue(results.stream().anyMatch(x->x.startsWith("success:")));
    }
    @Test public void cancelPreventsLateDiscoveryFromPublishing()throws Exception{
        delay=true;setup.event("settings.choices",event());assertTrue(read.await(5,TimeUnit.SECONDS));setup.event("settings.cancel",event());answer();assertEquals(0,publishes.get());
    }
    @Test public void configureInvalidationPreventsLateChoices()throws Exception{
        delay=true;setup.event("settings.choices",event());assertTrue(read.await(5,TimeUnit.SECONDS));setup.cancel();answer();assertEquals(0,publishes.get());
    }
    @Test public void olderHostDoesNotCrashOrStartDiscovery()throws Exception{
        oldHost=true;setup.event("settings.choices",event());java.lang.reflect.Field field=RemoteSetup.class.getDeclaredField("worker");field.setAccessible(true);((ExecutorService)field.get(setup)).submit(()->{}).get(5,TimeUnit.SECONDS);assertEquals(0,reads.get());
    }
    @Test public void expiredHostRunDoesNotStartNetworkWork()throws Exception{
        reject=true;setup.event("settings.choices",event());java.lang.reflect.Field field=RemoteSetup.class.getDeclaredField("worker");field.setAccessible(true);((ExecutorService)field.get(setup)).submit(()->{}).get(5,TimeUnit.SECONDS);assertEquals(0,reads.get());
    }
    @Test public void unrelatedEventsAreNotConsumed(){assertFalse(setup.event("ks.device.volume",Collections.emptyMap()));}
}
