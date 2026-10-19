package net.markandrew.kiosk.music;

import java.util.Locale;
import java.util.regex.*;
import org.json.JSONObject;

/** Plugin-owned descriptive metadata convention, not a Sendspin capability.
 * A connection range is not by itself proof of freshness or command execution. */
public final class VolumeCapability {
    private VolumeCapability(){}
    private static final Pattern VERSION=Pattern.compile("Music Assistant Plugin [0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}(?:-[A-Za-z0-9.-]{1,24})?");
    private static final Pattern TAG=Pattern.compile("(Music Assistant Plugin [0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}(?:-[A-Za-z0-9.-]{1,24})?) \\[ks-volume-v1=([0-9]{1,4}):([0-9]{1,4})\\]");
    public static final class Range {
        public final int min,max;
        private Range(int min,int max){this.min=min;this.max=max;}
        public String describe(){return min+"–"+max+" ("+(max-min)+" increments; "+String.format(Locale.ROOT,"%.2f%%",100d/max)+" per index step)";}
        /** Candidate Android nearest-index result; not a free-form error tolerance. */
        public int quantizedPercent(int requested){
            int index=(int)Math.round(Math.max(0,Math.min(100,requested))*max/100d);
            return (int)Math.round(Math.max(min,Math.min(max,index))*100d/max);
        }
        @Override public boolean equals(Object other){return other instanceof Range&&min==((Range)other).min&&max==((Range)other).max;}
        @Override public int hashCode(){return 31*min+max;}
    }
    public static Range range(int min,int max){return min>=0&&max>min&&max<=1000?new Range(min,max):null;}
    public static String advertise(String version,Range range){
        if(version==null||!VERSION.matcher(version).matches())throw new IllegalArgumentException("Invalid plugin software version");
        return version+(range==null?"":" [ks-volume-v1="+range.min+":"+range.max+"]");
    }
    public static Range parse(String software){
        if(software==null||software.length()>128)return null;
        Matcher match=TAG.matcher(software);if(!match.matches())return null;
        return range(Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3)));
    }
    public static Range fromPlayer(JSONObject player){
        if(player==null)return null;
        JSONObject info=player.optJSONObject("device_info");
        Object value=info==null?null:info.opt("software_version");
        return value instanceof String?parse((String)value):null;
    }
}
