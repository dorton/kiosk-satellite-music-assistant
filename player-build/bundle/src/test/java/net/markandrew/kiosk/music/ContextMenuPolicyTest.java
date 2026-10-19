package net.markandrew.kiosk.music;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class ContextMenuPolicyTest {
    @Test public void singleAddAndAlbumRemoveAreDirectButMultipleManagementActionsStayGrouped()throws Exception{
        ExecutorService worker=Executors.newSingleThreadExecutor();FeatureController.Ui ui=(FeatureController.Ui)java.lang.reflect.Proxy.newProxyInstance(FeatureController.Ui.class.getClassLoader(),new Class<?>[]{FeatureController.Ui.class},(p,m,a)->null);
        FeatureController c=new FeatureController(null,null,worker,new AtomicBoolean(),()->"ma",()->"speaker",()->true,ui);
        try{
            BrowserItem album=new BrowserItem(new JSONObject().put("name","Album").put("uri","library://album/1").put("media_type","album"));List<FeatureController.ItemAction> base=c.itemActions(album,null,Collections.emptyList(),null);
            FeatureApi.LibraryInfo info=new FeatureApi.LibraryInfo();info.canAdd=true;List<FeatureController.ItemAction> add=FeatureController.mergeLibraryActions(base,c.libraryActions(album,info));assertTrue(add.stream().anyMatch(a->a.label.equals("Add to library")));assertFalse(add.stream().anyMatch(a->a.label.equals("Manage library…")));
            info.canAdd=false;info.canRemove=true;List<FeatureController.ItemAction> choices=c.libraryActions(album,info);assertEquals(1,choices.size());assertEquals("Remove from library…",choices.get(0).label);List<FeatureController.ItemAction> remove=FeatureController.mergeLibraryActions(base,choices);assertFalse(remove.stream().anyMatch(a->a.label.contains("only")||a.label.equals("Manage library…")));
            info.deletion=new JSONObject().put("name","Provider");assertEquals(2,c.libraryActions(album,info).size());List<FeatureController.ItemAction> grouped=FeatureController.mergeLibraryActions(base,c.libraryActions(album,info));assertTrue(grouped.stream().anyMatch(a->a.label.equals("Manage library…")));
        }finally{c.close();worker.shutdownNow();}
    }
    @Test public void albumPlayOnChoosesThenRequestsGuardedReplacementNotAnotherMenu()throws Exception{
        final Runnable[] after={null};List<String> calls=new ArrayList<>();ExecutorService worker=Executors.newSingleThreadExecutor();
        FeatureController.Ui ui=(FeatureController.Ui)java.lang.reflect.Proxy.newProxyInstance(FeatureController.Ui.class.getClassLoader(),new Class<?>[]{FeatureController.Ui.class},(p,m,a)->{
            if(m.getName().equals("players")){calls.add("choose");after[0]=(Runnable)a[0];}
            if(m.getName().equals("play"))calls.add("play:"+a[1]);return null;
        });
        FeatureController c=new FeatureController(null,null,worker,new AtomicBoolean(),()->"ma",()->"speaker",()->true,ui);
        try{
            BrowserItem album=new BrowserItem(new JSONObject().put("name","Album").put("uri","library://album/1").put("media_type","album"));
            List<FeatureController.ItemAction> actions=c.itemActions(album,null,Collections.emptyList(),null);
            assertFalse(actions.stream().anyMatch(a->a.label.equals("Open")||a.label.contains("Favourites")));assertTrue(actions.stream().anyMatch(a->a.label.equals("Manage library…")));
            actions.stream().filter(a->a.label.equals("Play on…")).findFirst().get().action.run();assertEquals(Collections.singletonList("choose"),calls);assertNotNull(after[0]);after[0].run();assertEquals(Arrays.asList("choose","play:replace"),calls);
        }finally{c.close();worker.shutdownNow();}
    }
}
