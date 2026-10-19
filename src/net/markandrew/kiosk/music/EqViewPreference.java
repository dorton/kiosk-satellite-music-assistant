package net.markandrew.kiosk.music;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Last UI view only. Separate from staged DSP, presets, and receiver identity/trust. */
final class EqViewPreference {
    private static AtomicFile file(Context context){return new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(),"musicassistantplugin-eq-view-v1.txt"));}
    static boolean advanced(Context context)throws IOException{
        AtomicFile file=file(context);
        try(InputStream input=file.openRead()){
            byte[] bytes=new byte[17];int count=0,n;
            while(count<bytes.length&&(n=input.read(bytes,count,bytes.length-count))!=-1)count+=n;
            String value=new String(bytes,0,count,StandardCharsets.UTF_8);
            if("advanced".equals(value))return true;if("basic".equals(value))return false;
            throw new IOException("Saved EQ view could not be read. EQ values were not changed.");
        }catch(FileNotFoundException error){if(file.getBaseFile().exists()||new File(file.getBaseFile().getPath()+".bak").exists())throw error;return false;}
    }
    static void save(Context context,boolean advanced)throws IOException{
        synchronized(EqViewPreference.class){
            AtomicFile file=file(context);FileOutputStream output=null;
            try{output=file.startWrite();output.write((advanced?"advanced":"basic").getBytes(StandardCharsets.UTF_8));file.finishWrite(output);}
            catch(IOException error){if(output!=null)file.failWrite(output);throw error;}
            if(advanced(context)!=advanced)throw new IOException("EQ view save could not be verified.");
        }
    }
}
