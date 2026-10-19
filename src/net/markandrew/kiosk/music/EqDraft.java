package net.markandrew.kiosk.music;

import org.json.*;
import java.util.*;

/** A lossless, staged MA DSP document. View changes never translate or replace filters. */
final class EqDraft {
    static final String[] TYPES={"peak","low_shelf","high_shelf","high_pass","low_pass","notch"};
    final JSONObject baseline, config;
    double basicPreamp,advancedPreamp;
    private Boolean view;
    EqDraft(JSONObject raw)throws Exception{this(raw,raw);}
    private EqDraft(JSONObject raw,JSONObject edited)throws Exception{
        FeatureApi.require(raw.opt("enabled") instanceof Boolean && raw.optJSONArray("filters")!=null
            && raw.opt("input_gain") instanceof Number && raw.opt("output_gain") instanceof Number,"Incomplete DSP settings. Nothing was changed.");
        baseline=copy(raw);config=copy(edited);
        basicPreamp=advancedPreamp=raw.getDouble("input_gain");
        // A new Advanced profile starts at the actual Flat preset, including
        // zero preamp. Existing graphic banks keep their current gain unchanged.
        boolean graphic=false;JSONArray filters=raw.getJSONArray("filters");for(int i=0;i<filters.length();i++){JSONObject f=filters.optJSONObject(i);if(f!=null&&EqGraphic.canonical(f))graphic=true;}if(!graphic)advancedPreamp=0;
    }
    void select(boolean advanced)throws Exception{
        if(view!=null){if(view)advancedPreamp=config.getDouble("input_gain");else basicPreamp=config.getDouble("input_gain");}
        view=advanced;config.put("input_gain",advanced?advancedPreamp:basicPreamp);
    }
    void rememberPreamp()throws Exception{if(view!=null){if(view)advancedPreamp=config.getDouble("input_gain");else basicPreamp=config.getDouble("input_gain");}}
    EqDraft applied(boolean advanced)throws Exception{
        // Build a separate candidate. Pending resets must not move the visible
        // sliders, nor turn a failed/uncertain save into apparently applied data.
        EqDraft next=new EqDraft(baseline,config);next.basicPreamp=basicPreamp;next.advancedPreamp=advancedPreamp;next.view=advanced;next.rememberPreamp();
        EqGraphic old=new EqGraphic(new EqDraft(baseline)),g=new EqGraphic(next);
        boolean tone=old.tone()!=null&&old.tone().optBoolean("enabled"),bank=old.bank()!=null&&old.bank().optBoolean("enabled");
        if(next.config.getBoolean("enabled")){
            if(advanced&&tone)g.reset(false);else if(!advanced&&bank)g.reset(true);
            g.activate(advanced);
        }else{
            if(tone)g.reset(false);if(bank)g.reset(true);if(!tone&&!bank)g.reset(advanced);
            for(int i=0;i<next.filters().length();i++){JSONObject f=next.filters().getJSONObject(i);if("tone_control".equals(f.optString("type"))||EqGraphic.canonical(f))f.put("enabled",false);}
            next.config.put("input_gain",0);
        }
        next.validate();return next;
    }
    static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    JSONArray filters()throws Exception{return config.getJSONArray("filters");}
    JSONObject add(String type)throws Exception{
        JSONObject filter=FeatureApi.args("type",type,"enabled",true);
        if("tone_control".equals(type)){filter.put("bass_level",0.0);filter.put("mid_level",0.0);filter.put("treble_level",0.0);}
        else if("parametric_eq".equals(type)){filter.put("preamp",0.0);filter.put("per_channel_preamp",new JSONObject());filter.put("bands",new JSONArray());}
        else throw new IllegalArgumentException("Unsupported EQ filter.");
        filters().put(filter);return filter;
    }
    static JSONObject band()throws Exception{return FeatureApi.args("frequency",1000.0,"q",1.0,"gain",0.0,"type","peak","enabled",true,"channel","ALL");}
    static double number(String text,double min,double max){
        double value;try{value=Double.parseDouble(text.trim());}catch(RuntimeException e){throw new IllegalArgumentException("Enter a valid numeric EQ value.");}
        FeatureApi.require(Double.isFinite(value)&&value>=min&&value<=max,"EQ value must be between "+min+" and "+max+".");return value;
    }
    boolean changed(){return !same(baseline,config);}
    JSONObject candidate()throws Exception{
        JSONObject result=copy(config);result.put("preset_id",JSONObject.NULL);return result;
    }
    static void value(JSONObject row,String key,double min,double max)throws Exception{number(row.get(key).toString(),min,max);}
    void validate()throws Exception{
        value(config,"input_gain",-60,60);value(config,"output_gain",-60,60);
        JSONArray filters=filters();FeatureApi.require(filters.length()<=128,"Too many DSP filters to edit safely.");
        for(int i=0;i<filters.length();i++){
            JSONObject f=filters.getJSONObject(i);String type=f.optString("type");
            if("tone_control".equals(type)){for(String key:new String[]{"bass_level","mid_level","treble_level"})value(f,key,-60,60);}
            if("parametric_eq".equals(type)){
                if(!f.isNull("preamp"))value(f,"preamp",-60,60);
                JSONArray bands=f.getJSONArray("bands");FeatureApi.require(bands.length()<=128,"Too many EQ bands to edit safely.");
                for(int j=0;j<bands.length();j++){
                    JSONObject b=bands.getJSONObject(j);value(b,"frequency",0.000001,100000);value(b,"q",0.01,100);value(b,"gain",-60,60);
                    FeatureApi.require(Arrays.asList(TYPES).contains(b.optString("type")),"Unsupported EQ band type. Edit it in MA instead.");
                }
            }
        }
    }
    static boolean same(Object a,Object b){
        if(a instanceof Number && b instanceof Number)return Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0;
        if(a instanceof JSONObject && b instanceof JSONObject){
            JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;
            Iterator<String> keys=x.keys();while(keys.hasNext()){String k=keys.next();if(!y.has(k)||!same(x.opt(k),y.opt(k)))return false;}return true;
        }
        if(a instanceof JSONArray && b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!same(x.opt(i),y.opt(i)))return false;return true;}
        return Objects.equals(a,b);
    }
}
