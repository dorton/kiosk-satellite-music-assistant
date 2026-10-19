package net.markandrew.kiosk.music;

import java.util.*;
import org.json.*;

/** MA 2.10.x group level is the maximum of powered, available, adjustable members. */
final class GroupVolumeEvidence {
    final Map<String,Double> levels=new TreeMap<>();
    final Map<String,Boolean> mute=new TreeMap<>();
    final Map<String,String> participation=new TreeMap<>();
    final Map<String,VolumeCapability.Range> advertisedRanges=new TreeMap<>();
    String problem="";
    double maximum=-1;
    GroupVolumeEvidence(JSONArray rows,Set<String> members){
        for(String id:new TreeSet<>(members)){
            JSONObject row=FeatureApi.byId(rows,"player_id",id);
            if(row==null){problem="member missing from snapshot";continue;}
            String control=row.optString("volume_control");
            boolean eligible=row.optBoolean("available")&&row.optBoolean("enabled",true)&&!Boolean.FALSE.equals(row.opt("powered"))&&!"none".equals(control);
            participation.put(id,eligible+":"+control);
            if(!eligible)continue;
            Object value=row.opt("volume_level");
            if(control.isEmpty()||!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||((Number)value).doubleValue()<0||((Number)value).doubleValue()>100){problem="powered member volume/control missing or invalid";continue;}
            double level=((Number)value).doubleValue();levels.put(id,level);mute.put(id,row.optBoolean("volume_muted"));maximum=Math.max(maximum,level);
            VolumeCapability.Range range=VolumeCapability.fromPlayer(row);if(range!=null)advertisedRanges.put(id,range);
        }
        if(levels.isEmpty()&&problem.isEmpty())problem="no powered adjustable members";
    }
    boolean valid(){return problem.isEmpty();}
    boolean sameParticipants(GroupVolumeEvidence other){return participation.equals(other.participation)&&levels.keySet().equals(other.levels.keySet());}
    boolean onlyLocalMaximum(String own){for(Map.Entry<String,Double> e:levels.entrySet())if(e.getValue()==maximum&&!e.getKey().equals(own))return false;return levels.containsKey(own)&&levels.get(own)==maximum;}
    String signature(){StringBuilder s=new StringBuilder();for(String id:levels.keySet()){VolumeCapability.Range r=advertisedRanges.get(id);s.append(levels.get(id)).append(':').append(mute.get(id)).append(':').append(r==null?"unknown":r.min+":"+r.max).append(';');}return s.toString();}
    private List<String> order(String own){List<String> ids=new ArrayList<>(levels.keySet());if(ids.remove(own))ids.add(0,own);return ids;}
    String resolutionFailure(int requested,String own,int nativeStep){
        return resolutionFailure(this,requested,own,nativeStep);
    }
    private VolumeCapability.Range confirmedRange(GroupVolumeEvidence before,String id,String own,int nativeStep){
        VolumeCapability.Range r=advertisedRanges.get(id);
        Double initial=before.levels.get(id);
        // This convention describes native Android index rounding only. Nonzero
        // minima, external volume controls and inconsistent local steps stay unknown.
        return r!=null&&r.equals(before.advertisedRanges.get(id))&&r.min==0&&initial!=null&&Math.abs(initial-r.quantizedPercent((int)Math.round(initial)))<=1&&
            "true:native".equals(participation.get(id))&&
            (!id.equals(own)||Math.max(1,(int)Math.round(100d/r.max))==Math.max(1,nativeStep))?r:null;
    }
    int relativeTarget(int delta,String own,int nativeStep){
        int nominal=(int)Math.round(Math.max(0,Math.min(100,maximum+delta)));
        int direction=Integer.compare(delta,0);
        if(direction==0||nominal==maximum||!valid())return nominal;
        List<VolumeCapability.Range> ranges=new ArrayList<>();
        for(String id:levels.keySet()){
            VolumeCapability.Range range=confirmedRange(this,id,own,nativeStep);
            if(range==null)return nominal;
            ranges.add(range);
        }
        // MA's cached interpolation reference is not exposed. Do not infer which
        // member will receive the full group target from the current maximum.
        // Require that full target to cross the current maximum on EVERY known
        // native grid, so whichever reference maximum MA uses can make progress.
        // Search before submission only; no remembered intent and no write retry.
        for(int candidate=nominal;candidate>=0&&candidate<=100;candidate+=direction){
            boolean crosses=!ranges.isEmpty();
            for(VolumeCapability.Range range:ranges)if(direction*(range.quantizedPercent(candidate)-maximum)<=1){crosses=false;break;}
            if(crosses)return candidate;
        }
        // Preserve exact endpoint requests when the remaining distance is within
        // the reporting margin; the normal readback rules still decide success.
        return nominal;
    }
    String capabilityFailure(GroupVolumeEvidence before){
        return advertisedRanges.equals(before.advertisedRanges)?"":"advertised member volume range changed during verification";
    }
    String resolutionFailure(GroupVolumeEvidence before,int requested,String own,int nativeStep){
        int index=0;for(String id:order(own)){
            index++;VolumeCapability.Range r=confirmedRange(before,id,own,nativeStep);
            double upper=r==null?requested+(id.equals(own)?Math.max(1,Math.min(100,nativeStep)):1):r.quantizedPercent(requested)+1;
            if(levels.get(id)>upper)return (id.equals(own)?"local":"member "+index)+" above requested maximum ("+percent(levels.get(id))+", "+(r==null?(id.equals(own)?"local step "+nativeStep+"%":"remote step unknown; tolerance 1% "):"quantized target "+r.quantizedPercent(requested)+"%")+")";
        }return "";
    }
    boolean maximumMatches(GroupVolumeEvidence before,int requested,String own,int nativeStep){
        if(requested==0&&maximum!=0)return false;
        // Every tied maximum must satisfy its own rule. An unknown member may
        // not borrow a known coarse member's range, even when their levels tie.
        // A previously quieter member can reach the unchanged maximum through
        // MA's per-member interpolation without being sent the full group target.
        for(String id:levels.keySet())if(levels.get(id)==maximum){
            VolumeCapability.Range r=confirmedRange(before,id,own,nativeStep);
            double expected=r==null?requested:r.quantizedPercent(requested);
            int tolerance=r==null&&id.equals(own)&&onlyLocalMaximum(own)?Math.max(1,Math.min(100,nativeStep)):1;
            if(Math.abs(maximum-expected)>tolerance){
                Double initial=before.levels.get(id);
                boolean newlyTied=r!=null&&initial!=null&&initial<before.maximum&&maximum>initial+1&&requested>before.maximum&&
                    Math.abs(maximum-r.quantizedPercent((int)Math.round(maximum)))<=1&&
                    unchangedMaximumExplained(before,requested,own,nativeStep);
                boolean downwardTie=r!=null&&initial!=null&&initial<before.maximum&&maximum<=initial+1&&
                    Math.abs(maximum-r.quantizedPercent((int)Math.round(maximum)))<=1&&
                    descendingMaximumExplained(before,requested,own,nativeStep);
                if(!newlyTied&&!downwardTie)return false;
            }
        }
        return maximum>=0;
    }
    private boolean descendingMaximumExplained(GroupVolumeEvidence before,int requested,String own,int nativeStep){
        if(!valid()||!before.valid()||requested>=before.maximum||maximum>=before.maximum-1||!sameParticipants(before)||
            !capabilityFailure(before).isEmpty()||!directionFailure(before,own,-1).isEmpty())return false;
        boolean originalMaximum=false;
        for(String id:before.levels.keySet())if(before.levels.get(id)==before.maximum){
            originalMaximum=true;
            VolumeCapability.Range range=confirmedRange(before,id,own,nativeStep);
            double observed=levels.get(id);
            if(range==null||Math.abs(maximum-range.quantizedPercent(requested))>1||
                Math.abs(observed-range.quantizedPercent(requested))>1||before.maximum-observed<=1)return false;
        }
        return originalMaximum;
    }
    boolean unchangedMaximumExplained(GroupVolumeEvidence before,int requested,String own,int nativeStep){
        int direction=Double.compare(requested,before.maximum);
        if(direction==0||maximum!=before.maximum||!sameParticipants(before)||!capabilityFailure(before).isEmpty())return false;
        boolean moved=false;
        for(String id:levels.keySet()){
            double initial=before.levels.get(id),observed=levels.get(id);
            if(direction<0&&observed<initial-1||direction>0&&observed>initial+1)moved=true;
            if(initial==before.maximum){
                VolumeCapability.Range r=confirmedRange(before,id,own,nativeStep);
                if(r==null||Math.abs(initial-r.quantizedPercent(requested))>1||Math.abs(observed-r.quantizedPercent(requested))>1)return false;
            }
        }
        // Acknowledgement + all levels unchanged cannot distinguish a lost
        // adjustment from rounding. Require independent directional evidence.
        return moved;
    }
    String maximumRule(GroupVolumeEvidence before,int requested,String own,int nativeStep){
        for(String id:levels.keySet())if(levels.get(id)==maximum){VolumeCapability.Range r=confirmedRange(before,id,own,nativeStep);if(r!=null)return "advertised native quantized maximum "+r.quantizedPercent(requested)+"% (1% reporting margin; all maximum owners checked)";}
        return onlyLocalMaximum(own)?"local max tolerance "+Math.max(1,Math.min(100,nativeStep))+"%":"remote max step unknown; numeric tolerance 1%";
    }
    String directionFailure(GroupVolumeEvidence before,String own,int direction){
        if(!sameParticipants(before))return "powered/adjustable member set changed";
        List<String> failures=new ArrayList<>();int index=0;
        for(String id:order(own)){
            index++;double a=before.levels.get(id),b=levels.get(id);String label=id.equals(own)?"local":"member "+index;
            if(direction<0&&b>a+1||direction>0&&b<a-1)failures.add(label+" moved opposite direction ("+percent(a)+" to "+percent(b)+")");
            if(!Objects.equals(mute.get(id),before.mute.get(id)))failures.add(label+" mute flag changed");
        }
        if(failures.size()>3)return String.join("; ",failures.subList(0,3))+"; more member failures";
        return String.join("; ",failures);
    }
    String summary(GroupVolumeEvidence before,String own){
        List<String> parts=new ArrayList<>();int index=0,omitted=0;
        // Put the local member first for a readable bounded diagnostic.
        for(String id:order(own)){index++;if(parts.size()>=4){omitted++;continue;}Double initial=before.levels.get(id);
            VolumeCapability.Range range=advertisedRanges.get(id);
            parts.add((id.equals(own)?"local":"member "+index)+" "+(initial==null?"unknown":percent(initial))+" to "+percent(levels.get(id))+(levels.get(id)==maximum?" (max)":"")+" [advertised range "+(range==null?"unknown":range.describe())+"; connection snapshot]");}
        if(omitted>0)parts.add(omitted+" more members");return String.join("; ",parts);
    }
    static String percent(double level){return String.format(Locale.ROOT,"%.2f%%",level);}
}
