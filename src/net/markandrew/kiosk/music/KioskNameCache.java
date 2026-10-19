package net.markandrew.kiosk.music;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Only a display-name cache; never a receiver identity, trust key or saved user override. */
final class KioskNameCache {
    private final AtomicFile file;
    KioskNameCache(Context context){file=new AtomicFile(new File(context.getNoBackupFilesDir(),"musicassistantplugin-kiosk-name-v1.txt"));}
    String read(){try{byte[] data=file.readFully();return data.length<=512?ReceiverNamePolicy.override(new String(data,StandardCharsets.UTF_8)):"";}catch(IOException ignored){return "";}}
    void remember(String name){
        String clean=ReceiverNamePolicy.override(name);if(clean.isEmpty())return;
        FileOutputStream output=null;
        try{output=file.startWrite();output.write(clean.getBytes(StandardCharsets.UTF_8));file.finishWrite(output);}catch(IOException ignored){if(output!=null)file.failWrite(output);}
    }
}
