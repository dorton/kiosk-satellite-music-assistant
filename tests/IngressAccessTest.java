package net.markandrew.kiosk.music;

import java.util.*;
import me.jxl.kiosk.plugins.PluginHost;

/** SDK bridge only, no credentials or network. Normal-user ingress endpoints only. */
public final class IngressAccessTest {
    static int checks;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("Ingress bridge "+checks);}
    static Map<String,Object> map(Object... pairs){Map<String,Object> r=new HashMap<>();for(int i=0;i<pairs.length;i+=2)r.put((String)pairs[i],pairs[i+1]);return r;}
    static final class Host implements PluginHost {
        String user="user",denied="";int sessions;boolean badShape;
        final List<String> endpoints=new ArrayList<>();
        public void executeCommand(String cmd,Map<String,Object> args,CommandCallback cb){cb.onResult(true,map("homeAssistantUrl","https://ha.example/"),null);}
        public void haCall(Map<String,Object> msg,CommandCallback cb){
            if(msg.get("type").equals("auth/current_user")){cb.onResult(true,map("id",user),null);return;}
            String path=(String)msg.get("endpoint");endpoints.add(path);
            check(path.equals("/addons/ma/info")||path.equals("/ingress/session"));
            if(path.equals(denied)){cb.onResult(false,map("code","unauthorized","message","private raw text"),"private raw text");return;}
            if(path.equals("/addons/ma/info"))cb.onResult(true,map("ingress",true,"ingress_url","/api/hassio_ingress/fixture-path"),null);
            else{sessions++;cb.onResult(true,badShape?Collections.emptyList():map("session","fixture-session"),null);}
        }
    }
    public static void main(String[] args)throws Exception{
        Host host=new Host();IngressAccess access=new IngressAccess(host);IngressAccess.Session[] result={null};String[] error={""};
        access.ensure("ma",()->true,s->result[0]=s,e->error[0]=e);
        check(result[0]!=null&&error[0].isEmpty());check(host.sessions==1);
        check(result[0].base.toString().equals("https://ha.example/api/hassio_ingress/fixture-path/"));
        IngressAccess.Session first=result[0];access.ensure("ma",()->true,s->result[0]=s,e->error[0]=e);
        check(result[0]==first&&host.sessions==1);
        java.lang.reflect.Field created=IngressAccess.Session.class.getDeclaredField("createdAt");created.setAccessible(true);created.setLong(first,System.nanoTime()-java.util.concurrent.TimeUnit.MINUTES.toNanos(6));
        access.ensure("ma",()->true,s->result[0]=s,e->error[0]=e);check(result[0]!=first&&host.sessions==2);
        host.user="other";result[0]=null;access.ensure("ma",()->true,s->result[0]=s,e->error[0]=e);
        check(result[0]==null&&error[0].contains("identity or server changed"));
        host.user="user";host.denied="/ingress/session";error[0]="";
        access.ensure("ma",()->true,s->result[0]=s,e->error[0]=e);
        check(error[0].contains("/ingress/session")&&error[0].contains("unauthorized"));check(!error[0].contains("private raw text"));
        host.denied="";host.badShape=true;error[0]="";
        access.ensure("ma",()->true,s->result[0]=s,e->error[0]=e);check(error[0].contains("unexpected response shape"));
        host.badShape=false;error[0]="";
        access.ensure("ma",()->true,s->{throw new IllegalStateException("private raw text");},e->error[0]=e);
        check(error[0].contains("Ingress connected")&&!error[0].contains("private raw text"));
        error[0]="";access.ensure("ma",()->false,s->{throw new AssertionError();},e->error[0]=e);check(error[0].isEmpty());
        error[0]="";int calls=host.endpoints.size();access.ensure("",()->true,s->{throw new AssertionError();},e->error[0]=e);
        check(error[0].contains("app is not selected")&&host.endpoints.size()==calls);
        System.out.println("PASS: "+checks+" normal-user ingress, expiry and diagnostic checks");
    }
}
