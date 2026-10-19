package net.markandrew.kiosk.music;

import java.net.URI;
import org.json.JSONObject;

/** Live display only. Station identity, favourites and queue actions stay unchanged. */
final class RadioMetadata {
    static JSONObject current(JSONObject player,JSONObject queue,JSONObject item,JSONObject media){
        if(!"radio".equals(media.optString("media_type")))return new JSONObject();
        JSONObject value=player.optJSONObject("current_media");
        if(value!=null){
            String source=value.optString("source_id"),id=value.optString("queue_item_id");
            boolean sourceMatches=source.isEmpty()||source.equals(queue.optString("queue_id"));
            boolean itemMatches=!id.isEmpty()?id.equals(item.optString("queue_item_id")):!value.optString("uri").isEmpty()&&value.optString("uri").equals(media.optString("uri"));
            if(sourceMatches&&itemMatches)return value;
        }
        JSONObject details=item.optJSONObject("streamdetails");
        value=details==null?null:details.optJSONObject("stream_metadata");
        return value==null?new JSONObject():value;
    }
    static String text(JSONObject data,String key,String fallback){
        String value=data.optString(key,"").trim();return value.isEmpty()?fallback:BrowserPage.label(value);
    }
    static String image(String value){
        if(value.isEmpty()||value.length()>4096)return "";
        try{
            URI uri=new URI(value);String path=uri.getPath();
            if(path!=null&&path.matches("(?:.*/)?imageproxy/[a-f0-9]{64}/?")){
                String id=path.substring(path.indexOf("imageproxy/")+11).replace("/","");
                return "imageproxy/"+id+"?size=256";
            }
            return "https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&uri.getRawUserInfo()==null&&uri.getFragment()==null?uri.toASCIIString():"";
        }catch(Exception ignored){return "";}
    }
}
