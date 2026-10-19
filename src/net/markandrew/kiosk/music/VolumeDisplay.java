package net.markandrew.kiosk.music;

import org.json.*;

/** Presentation only: never turn a zero-level indication into an MA mute write. */
final class VolumeDisplay {
    static boolean zero(JSONObject row,String key){Object value=row.opt(key);return value instanceof Number&&((Number)value).doubleValue()==0;}
    static boolean silent(JSONObject row,boolean group){return row.optBoolean(group?"group_volume_muted":"volume_muted")||(group?row.optBoolean("plugin_all_members_zero"):zero(row,"volume_level"));}
    static void annotate(JSONArray rows)throws Exception{
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);java.util.Set<String> members=new java.util.HashSet<>();
            if(!"group".equals(row.optString("type"))&&!row.optString("player_id").isEmpty())members.add(row.optString("player_id"));
            JSONArray ids=row.optJSONArray("group_members");if(ids!=null)for(int j=0;j<ids.length();j++)if(!ids.optString(j).isEmpty())members.add(ids.optString(j));
            boolean zero=!members.isEmpty();
            for(String id:members){JSONObject member=null;for(int j=0;j<rows.length();j++){JSONObject candidate=rows.getJSONObject(j);if(id.equals(candidate.optString("player_id"))){member=candidate;break;}}if(member==null||!zero(member,"volume_level")){zero=false;break;}}
            row.put("plugin_all_members_zero",zero);
        }
    }
}
