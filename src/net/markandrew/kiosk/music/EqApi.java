package net.markandrew.kiosk.music;

import org.json.*;

/** Exact-player DSP reads and one guarded save; no queue/leader redirection. */
final class EqApi {
    static final class State {
        final IngressAccess.Session session;
        final String player,name,user,server,version,provider,output;
        final boolean writable;
        final EqDraft draft;
        State(IngressAccess.Session session,String player,JSONObject row,JSONObject account,JSONObject info,boolean writable,EqDraft draft){
            this.session=session;this.player=player;name=row.optString("name",player);user=account.optString("user_id");server=info.optString("server_id");version=info.optString("server_version");
            provider=row.optString("provider");output=row.optString("active_output_protocol");this.writable=writable;this.draft=draft;
        }
        private State(State s,EqDraft draft){session=s.session;player=s.player;name=s.name;user=s.user;server=s.server;version=s.version;provider=s.provider;output=s.output;writable=s.writable;this.draft=draft;}
        State withDraft(EqDraft draft){return new State(this,draft);}
    }
    static boolean permitted(JSONObject scopes,JSONObject user,String scope){return FeatureApi.contains(scopes.optJSONArray(user.optString("role")),"*")||FeatureApi.contains(scopes.optJSONArray(user.optString("role")),scope);}
    static void target(JSONObject row,String player){
        FeatureApi.require(player.equals(row.optString("player_id"))&&Boolean.TRUE.equals(row.opt("available"))&&!row.optString("provider").isEmpty(),"Selected player identity or availability could not be verified.");
        FeatureApi.require("player".equals(row.optString("type"))||"stereo_pair".equals(row.optString("type")),"EQ is per speaker. Select an individual player, not a group; no group leader was substituted.");
    }
    State load(FeatureApi api,FeatureApi.Connection c,String player)throws Exception{
        JSONObject info=api.api.serverInfo(c.session),user=(JSONObject)api.api.identity(c.session);
        FeatureApi.require(!user.optString("user_id").isEmpty(),"MA account identity is unavailable.");
        FeatureApi.require(info.optString("server_version").matches("2\\.10\\.[0-9]+(?:[.-].*)?"),"This EQ editor currently supports MA 2.10.x. Use MA's DSP editor for this server version.");
        JSONObject scopes=c.object("auth/scopes",FeatureApi.args());
        FeatureApi.require(permitted(scopes,user,"config.players.read"),"This MA account cannot read EQ settings (config.players.read required). No permissions were changed.");
        JSONArray allowed=user.optJSONArray("player_filter");
        FeatureApi.require(allowed!=null&&(allowed.length()==0||FeatureApi.contains(allowed,player)),"Selected player access could not be verified for this account.");
        JSONObject row=c.object("players/get",FeatureApi.args("player_id",player));target(row,player);
        EqDraft draft=new EqDraft(c.object("config/players/dsp/get",FeatureApi.args("player_id",player)));
        draft.validate();
        return new State(c.session,player,row,user,info,permitted(scopes,user,"config.players.write"),draft);
    }
    void verify(FeatureApi api,FeatureApi.Connection c,String player,State expected)throws Exception{
        FeatureApi.require(expected.session==c.session&&expected.player.equals(player),"Selected player or HA session changed. Reopen EQ; nothing was submitted.");
        expected.draft.validate();
        State fresh=load(api,c,player);
        FeatureApi.require(fresh.writable&&expected.writable,"This account cannot save EQ (config.players.write required).");
        FeatureApi.require(expected.user.equals(fresh.user)&&expected.server.equals(fresh.server)&&expected.version.equals(fresh.version)
            &&expected.provider.equals(fresh.provider)&&expected.output.equals(fresh.output),"Account, MA server or player output changed. Reopen EQ before applying.");
        FeatureApi.require(EqDraft.same(expected.draft.baseline,fresh.draft.baseline),"EQ was changed elsewhere. Reopen it to review the latest settings; nothing was overwritten.");
    }
    State save(FeatureApi api,FeatureApi.Connection c,String player,State expected)throws Exception{
        FeatureApi.require(expected.draft.changed(),"No EQ changes to apply.");verify(api,c,player,expected);
        JSONObject candidate=expected.draft.candidate();
        c.write("config/players/dsp/save",FeatureApi.args("player_id",player,"config",candidate));
        State verified=load(api,c,player);
        FeatureApi.require(expected.user.equals(verified.user)&&expected.server.equals(verified.server)&&expected.provider.equals(verified.provider)
            &&expected.version.equals(verified.version)&&expected.output.equals(verified.output)&&verified.writable
            &&EqDraft.same(candidate,verified.draft.baseline),"EQ save is unverified. Check MA before retrying; no automatic retry was made.");
        return verified;
    }
}
