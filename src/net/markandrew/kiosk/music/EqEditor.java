package net.markandrew.kiosk.music;

import java.util.*;
import java.util.function.*;
import android.widget.Button;
import org.json.*;

/** Exactly two native views: Basic tone controls and Advanced fixed ten-band EQ. */
final class EqEditor {
    private final TouchPanel panel;private final EqGraphic graphic;private final Consumer<Boolean> redraw;
    EqEditor(TouchPanel panel,EqDraft draft,boolean advanced,EqPresetStore presets,Consumer<Boolean> redraw,Runnable make,Runnable manage,Runnable apply,Runnable cancel)throws Exception{
        this.panel=panel;this.graphic=new EqGraphic(draft);this.redraw=redraw;
        panel.eqPresentation();
        draft.select(advanced);
        panel.eqHeader(draft.config.getBoolean("enabled"),advanced,v->edit(()->draft.config.put("enabled",v)),()->redraw.accept(!advanced));
        if(advanced){
            EqPresets.Curve curve=new EqPresets.Curve(graphic.preamp(),graphic.gains());
            String selected=EqPresets.match(curve,presets.custom);
            List<String> names=new ArrayList<>();names.add("Make Preset…");names.add("Edit List…");names.add("Manual");names.addAll(EqPresets.BUILTINS.keySet());names.addAll(presets.custom.keySet());
            Button picker=panel.eqChoice(selected,names.toArray(new String[0]),name->{
                if("Make Preset…".equals(name)){make.run();return;}if("Edit List…".equals(name)){manage.run();return;}if("Manual".equals(name))return;
                edit(()->{EqPresets.Curve choice=EqPresets.BUILTINS.get(name);if(choice==null)choice=presets.custom.get(name);FeatureApi.require(choice!=null,"Preset is no longer available.");graphic.preset(choice);redraw.accept(true);});
            });
            String[] labels=new String[11];double[] values=new double[11];labels[0]="Preamp";values[0]=graphic.preamp();double[] gains=graphic.gains();
            for(int i=0;i<10;i++){labels[i+1]=EqGraphic.LABELS[i];values[i+1]=gains[i];}
            panel.eqSliders(labels,values,(i,v)->edit(()->{if(i==0)graphic.preamp(v);else graphic.band(i-1,v);picker.setText("Manual ▾");}));
        }else{
            JSONObject tone=graphic.tone();double[] values={graphic.preamp(),tone==null?0:tone.getDouble("bass_level"),tone==null?0:tone.getDouble("mid_level"),tone==null?0:tone.getDouble("treble_level")};
            panel.eqSliders(new String[]{"Preamp","Bass","Mid","Treble"},values,(i,v)->edit(()->{if(i==0)graphic.preamp(v);else graphic.ensureTone().put(new String[]{"bass_level","mid_level","treble_level"}[i-1],v);}));
        }
        panel.eqActions(()->edit(()->{draft.validate();apply.run();}),cancel);
    }
    interface Action {void run()throws Exception;}
    private void edit(Action action){try{action.run();}catch(Exception error){panel.message(error.getMessage()==null?"EQ could not be edited.":error.getMessage());}}
}
