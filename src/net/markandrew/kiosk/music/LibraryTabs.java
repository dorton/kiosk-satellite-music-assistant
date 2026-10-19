package net.markandrew.kiosk.music;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Kiosk-local Library display choices, separate from plugin/native preferences. */
final class LibraryTabs {
    static final List<String> KEYS=Collections.unmodifiableList(Arrays.asList("playlists","albums","artists","tracks","radio","podcasts","audiobooks"));
    final Set<String> visible=new LinkedHashSet<>(KEYS);
    private final AtomicFile file;
    private final String baseline;
    LibraryTabs(Context context)throws Exception{
        file=new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(),"musicassistantplugin-library-tabs-v1.json"));baseline=read();
        JSONObject raw=new JSONObject(baseline);JSONArray tabs=raw.optJSONArray("visible");FeatureApi.require(!raw.has("visible")||tabs!=null,"Invalid saved Library tabs.");if(tabs==null)return;
        visible.clear();for(int i=0;i<tabs.length();i++){String key=tabs.getString(i);FeatureApi.require(KEYS.contains(key),"Unknown saved Library tab.");visible.add(key);}validate();
    }
    static String label(String key){return Character.toUpperCase(key.charAt(0))+key.substring(1);}
    private String read()throws Exception{
        try(InputStream in=file.openRead()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[512];int n;while((n=in.read(b))!=-1){FeatureApi.require(out.size()+n<=4096,"Saved Library tab data is too large.");out.write(b,0,n);}return out.toString("UTF-8");
        }catch(FileNotFoundException e){if(file.getBaseFile().exists()||new File(file.getBaseFile().getPath()+".bak").exists())throw e;return "{}";}
    }
    private void validate(){FeatureApi.require(!visible.isEmpty()&&KEYS.containsAll(visible),"Keep at least one Library tab visible.");}
    void commit()throws Exception{
        validate();synchronized(LibraryTabs.class){
            FeatureApi.require(EqDraft.same(new JSONObject(baseline),new JSONObject(read())),"Library settings changed elsewhere. Reopen them before saving.");
            JSONObject value=new JSONObject().put("visible",new JSONArray(visible));byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
            FileOutputStream out=null;try{out=file.startWrite();out.write(bytes);file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
            FeatureApi.require(EqDraft.same(value,new JSONObject(read())),"Library settings save is unverified. Reopen to check before retrying.");
        }
    }
}
