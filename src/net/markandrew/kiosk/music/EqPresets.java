package net.markandrew.kiosk.music;

import java.util.*;
import org.json.*;

/** Original approximate curves transcribed from the user's reference images. */
final class EqPresets {
    static final class Curve {
        final double preamp;final double[] gains;
        Curve(double preamp,double... gains){this.preamp=preamp;this.gains=gains.clone();}
        void validate(){FeatureApi.require(gains.length==10,"A graphic preset needs ten bands.");EqDraft.number(Double.toString(preamp),-12,12);for(double g:gains)EqDraft.number(Double.toString(g),-12,12);}
        JSONObject json()throws Exception{JSONArray values=new JSONArray();for(double gain:gains)values.put(gain);return FeatureApi.args("preamp",preamp,"gains",values);}
        static Curve from(JSONObject raw)throws Exception{JSONArray values=raw.getJSONArray("gains");FeatureApi.require(values.length()==10,"Invalid custom preset.");double[] gains=new double[10];for(int i=0;i<10;i++)gains[i]=values.getDouble(i);Curve c=new Curve(raw.getDouble("preamp"),gains);c.validate();return c;}
    }
    static final Map<String,Curve> BUILTINS;
    static {
        Map<String,Curve> m=new LinkedHashMap<>();
        add(m,"Acoustic",5,4.5,3.5,1,2,1.5,3,3.5,3,1.5);
        add(m,"Classical",5,4,3,2.5,-1.5,-1.5,0,2,3,3.5);
        add(m,"Dance",3.5,6.5,5,0,1.5,3.5,5,4.5,3.5,0);
        add(m,"Deep",5,3.5,1.5,0.5,2.5,2.5,1.5,-2,-3.5,-4.5);
        add(m,"Electronic",4.5,4,1,0,-2.5,2,1,1,4,5);
        add(m,"Flat",0,0,0,0,0,0,0,0,0,0);
        add(m,"Hip-Hop",5,4,1.5,3,-1,-1,1.5,-0.5,2,3);
        add(m,"Increase Bass",5.5,4,3.5,2.5,1,0,0,0,0,0);
        add(m,"Increase Treble",0,0,0,0,0,1.5,2.5,3.5,4,5.5);
        add(m,"Increase Vocals",-1.5,-3,-3,1.5,4,4,3,1.5,0,-1.5);
        add(m,"Jazz",4,3,1.5,2,-1.5,-1.5,0,1.5,3,4);
        add(m,"Latin",4.5,3,0,0,-1.5,-1.5,-1.5,0,3,4.5);
        add(m,"Loudness",6,4,0,0,-2,0,-1,-5,5,1);
        add(m,"Lounge",-3,-1.5,-0.5,1.5,4,2.5,0,-1.5,2,1);
        add(m,"Piano",3,2,0,2.5,3,1.5,3.5,4.5,3,3.5);
        add(m,"Pop",-1.5,-1,0,2,4,4,2,0,-1,-1.5);
        add(m,"R&B",2.5,7,5.5,1,-2.5,-2,2,2.5,3,4);
        add(m,"Reduce Bass",-5.5,-4,-3.5,-2.5,-1,0,0,0,0,0);
        add(m,"Reduce Treble",0,0,0,0,0,-1.5,-2.5,-3.5,-4,-5.5);
        add(m,"Rock",5,4,3,1.5,-1,-1.5,0,2,3,4.5);
        add(m,"Small Speakers",5.5,4,3.5,2.5,1,0,-1.5,-2.5,-3.5,-4);
        add(m,"Spoken Word",-3.5,-0.5,0,0.5,3,4.5,5,4,2.5,0);
        BUILTINS=Collections.unmodifiableMap(m);
    }
    private static void add(Map<String,Curve> map,String name,double... gains){Curve c=new Curve(0,gains);c.validate();map.put(name,c);}
    static String name(String name){String value=name.trim();FeatureApi.require(!value.isEmpty()&&value.length()<=80,"Use a preset name of 1–80 characters.");for(int i=0;i<value.length();i++)FeatureApi.require(!Character.isISOControl(value.charAt(i)),"Preset names cannot contain control characters.");return value;}
    static boolean reserved(String name){if("Manual".equalsIgnoreCase(name))return true;for(String key:BUILTINS.keySet())if(key.equalsIgnoreCase(name))return true;return false;}
    static String match(Curve current,Map<String,Curve> custom)throws Exception{
        for(Map<String,Curve> all:Arrays.asList(BUILTINS,custom))for(Map.Entry<String,Curve> e:all.entrySet())if(EqDraft.same(current.json(),e.getValue().json()))return e.getKey();return "Manual";
    }
}
