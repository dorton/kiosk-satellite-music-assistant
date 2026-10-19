package net.markandrew.kiosk.music;

import org.json.*;

/** A fixed octave bank alongside, never in place of, unrelated MA filters. */
final class EqGraphic {
    static final double Q=1.4142135623730951;
    static final int[] HZ={32,64,125,250,500,1000,2000,4000,8000,16000};
    static final String[] LABELS={"32","64","125","250","500","1k","2k","4k","8k","16k"};
    final EqDraft draft;
    EqGraphic(EqDraft draft){this.draft=draft;}
    JSONObject tone()throws Exception{
        for(int i=0;i<draft.filters().length();i++){JSONObject f=draft.filters().getJSONObject(i);if("tone_control".equals(f.optString("type")))return f;}return null;
    }
    JSONObject bank()throws Exception{
        for(int i=0;i<draft.filters().length();i++){JSONObject f=draft.filters().getJSONObject(i);if(canonical(f))return f;}return null;
    }
    static boolean canonical(JSONObject f){
        if(!"parametric_eq".equals(f.optString("type"))||f.optDouble("preamp",0)!=0)return false;
        JSONObject channels=f.optJSONObject("per_channel_preamp");if(channels==null||channels.length()!=0)return false;
        JSONArray bands=f.optJSONArray("bands");if(bands==null||bands.length()!=HZ.length)return false;
        for(int i=0;i<HZ.length;i++){
            JSONObject b=bands.optJSONObject(i);if(b==null||b.optDouble("frequency")!=HZ[i]||Math.abs(b.optDouble("q")-Q)>0.000001
                ||!"peak".equals(b.optString("type"))||!"ALL".equals(b.optString("channel"))||!Boolean.TRUE.equals(b.opt("enabled")))return false;
        }return true;
    }
    JSONObject ensureBank()throws Exception{
        JSONObject f=bank();if(f!=null)return f;
        f=draft.add("parametric_eq");JSONArray bands=f.getJSONArray("bands");
        for(int hz:HZ)bands.put(EqDraft.band().put("frequency",hz).put("q",Q));return f;
    }
    JSONObject ensureTone()throws Exception{JSONObject f=tone();return f==null?draft.add("tone_control"):f;}
    double preamp(){return draft.config.optDouble("input_gain");}
    void preamp(double gain)throws Exception{draft.config.put("input_gain",EqDraft.number(Double.toString(gain),-60,60));}
    double[] gains()throws Exception{double[] values=new double[10];JSONObject f=bank();if(f!=null)for(int i=0;i<10;i++)values[i]=f.getJSONArray("bands").getJSONObject(i).getDouble("gain");return values;}
    void band(int index,double gain)throws Exception{ensureBank().getJSONArray("bands").getJSONObject(index).put("gain",EqDraft.number(Double.toString(gain),-12,12));}
    void preset(EqPresets.Curve curve)throws Exception{
        curve.validate();JSONObject f=ensureBank();for(int i=0;i<10;i++)f.getJSONArray("bands").getJSONObject(i).put("gain",curve.gains[i]);
        preamp(curve.preamp);
    }
    void activate(boolean advanced)throws Exception{
        // Preserve curves, including arbitrary parametric filters. Only the tone
        // controls and our recognizable ten-band bank are mutually exclusive.
        JSONObject selected=advanced?ensureBank():ensureTone();
        for(int i=0;i<draft.filters().length();i++){
            JSONObject f=draft.filters().getJSONObject(i);
            if((advanced&&"tone_control".equals(f.optString("type")))||(!advanced&&canonical(f)))f.put("enabled",false);
        }
        selected.put("enabled",true);
        draft.rememberPreamp();
    }
    void reset(boolean advanced)throws Exception{
        JSONObject f=advanced?bank():tone();
        if(f!=null){
            if(advanced)for(int i=0;i<10;i++)f.getJSONArray("bands").getJSONObject(i).put("gain",0);
            else for(String key:new String[]{"bass_level","mid_level","treble_level"})f.put(key,0);
            f.put("enabled",false);
        }
        if(advanced)draft.advancedPreamp=0;else draft.basicPreamp=0;
        // The shared MA gain belongs to the new selected type when switching.
    }
}
