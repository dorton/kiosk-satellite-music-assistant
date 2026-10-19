package net.markandrew.kiosk.music;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Per-account/server/player preamps; DSP curves remain in MA, even when bypassed. */
final class EqModeStore {
    private final AtomicFile file;
    private final String baseline,key;
    private final JSONObject entries;
    EqModeStore(Context context,EqApi.State state)throws Exception{
        file=new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(),"musicassistantplugin-eq-modes-v1.json"));
        key=new JSONArray(new String[]{state.server,state.user,state.player}).toString();
        baseline=read();entries=new JSONObject(baseline);
    }
    private String read()throws Exception{
        try(InputStream in=file.openRead()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;
            while((n=in.read(buffer))!=-1){FeatureApi.require(out.size()+n<=2097152,"Saved EQ mode data is too large.");out.write(buffer,0,n);}return out.toString("UTF-8");
        }catch(FileNotFoundException e){if(file.getBaseFile().exists()||new File(file.getBaseFile().getPath()+".bak").exists())throw e;return "{}";}
    }
    void restore(EqDraft draft)throws Exception{
        JSONObject row=entries.optJSONObject(key);
        // A remote DSP edit wins over stale local preamp memories.
        if(row==null||!EqDraft.same(row.optJSONObject("dsp"),draft.baseline))return;
        double basic=EqDraft.number(row.get("basic").toString(),-60,60),advanced=EqDraft.number(row.get("advanced").toString(),-60,60);
        draft.basicPreamp=basic;draft.advancedPreamp=new EqGraphic(draft).bank()==null?0:advanced;
    }
    void commit(EqDraft draft,JSONObject verified)throws Exception{
        synchronized(EqModeStore.class){
            FeatureApi.require(EqDraft.same(new JSONObject(baseline),new JSONObject(read())),"Remembered EQ modes changed elsewhere. Reopen EQ; MA may already be saved.");
            entries.put(key,FeatureApi.args("dsp",verified,"basic",draft.basicPreamp,"advanced",draft.advancedPreamp));
            byte[] bytes=entries.toString().getBytes(StandardCharsets.UTF_8);FeatureApi.require(bytes.length<=2097152,"Saved EQ mode data is too large.");
            FileOutputStream out=null;try{out=file.startWrite();out.write(bytes);file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
            FeatureApi.require(EqDraft.same(entries,new JSONObject(read())),"EQ mode save is unverified. Reopen EQ before retrying.");
        }
    }
}
