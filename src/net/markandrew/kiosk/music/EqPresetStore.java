package net.markandrew.kiosk.music;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Non-secret plugin-local curve library. No receiver trust or native preferences. */
final class EqPresetStore {
    private final AtomicFile file;
    private final String baseline;
    final Map<String,EqPresets.Curve> custom=new LinkedHashMap<>();
    EqPresetStore(Context context)throws Exception{
        file=new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(),"musicassistantplugin-eq-presets-v1.json"));
        baseline=read();JSONObject raw=new JSONObject(baseline);
        Iterator<String> keys=raw.keys();while(keys.hasNext()){String name=keys.next();FeatureApi.require(!EqPresets.reserved(name),"Invalid custom preset library.");custom.put(EqPresets.name(name),EqPresets.Curve.from(raw.getJSONObject(name)));}
        FeatureApi.require(custom.size()<=64,"Too many custom presets.");
    }
    private String read()throws IOException{
        try(InputStream input=file.openRead();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[2048];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>65536)throw new IOException("Preset library is too large.");output.write(buffer,0,n);}return output.toString("UTF-8");
        }catch(FileNotFoundException error){if(file.getBaseFile().exists()||new File(file.getBaseFile().getPath()+".bak").exists())throw error;return "{}";}
    }
    private JSONObject json()throws Exception{JSONObject raw=new JSONObject();for(Map.Entry<String,EqPresets.Curve> e:custom.entrySet())raw.put(e.getKey(),e.getValue().json());return raw;}
    boolean changed()throws Exception{return !EqDraft.same(new JSONObject(baseline),json());}
    private void unique(String name,String except){FeatureApi.require(!EqPresets.reserved(name),"Built-in preset names are protected.");for(String key:custom.keySet())FeatureApi.require(key.equals(except)||!key.equalsIgnoreCase(name),"A custom preset already has that name.");}
    void make(String text,EqPresets.Curve curve){String name=EqPresets.name(text);unique(name,null);curve.validate();FeatureApi.require(custom.size()<64,"Custom preset limit reached (64).");custom.put(name,curve);}
    void rename(String old,String text){FeatureApi.require(custom.containsKey(old),"Only custom presets can be renamed.");String name=EqPresets.name(text);unique(name,old);EqPresets.Curve curve=custom.remove(old);custom.put(name,curve);}
    void delete(String name){FeatureApi.require(custom.containsKey(name),"Only custom presets can be deleted.");custom.remove(name);}
    void verifyUnchanged()throws IOException{FeatureApi.require(baseline.equals(read()),"Custom presets changed elsewhere. Reopen EQ before applying.");}
    void commit()throws Exception{
        synchronized(EqPresetStore.class){
            verifyUnchanged();if(!changed())return;String raw=json().toString();byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);FeatureApi.require(bytes.length<=65536,"Custom preset library is too large.");
            FileOutputStream output=null;
            try{output=file.startWrite();output.write(bytes);file.finishWrite(output);}catch(Exception error){if(output!=null)file.failWrite(output);throw error;}
            FeatureApi.require(raw.equals(read()),"Custom preset save is unverified.");
        }
    }
}
