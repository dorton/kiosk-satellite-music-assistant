package net.markandrew.kiosk.music;

/** Local gain only. Never changes Android, Kiosk or MA's saved volume. */
public final class AudioGainPolicy {
    private final java.util.Set<String> interactions=new java.util.HashSet<>();
    private float volume=1f;
    private int percent=10, focus=1;
    public static int configuredPercent(Object value){
        try{double n=Double.parseDouble(String.valueOf(value));return Double.isFinite(n)&&n>=1&&n<=10?(int)Math.round(n):10;}
        catch(RuntimeException ignored){return 10;}
    }
    public void percent(int value){percent=Math.max(1,Math.min(10,value));}
    public void volume(float value){volume=Math.max(0f,Math.min(1f,value));}
    public void interaction(String source,boolean active){if(active)interactions.add(source);else interactions.remove(source);}
    public void focus(int value){focus=value;}
    public boolean interrupted(){return focus==-1||(focus!=1&&focus!=-3&&interactions.isEmpty());}
    public float gain(){return volume*(!interactions.isEmpty()||focus==-3?percent/100f:1f);}
}
