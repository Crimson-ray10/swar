package in.itantra.mobile;
import org.json.*;
final class Events {
    static JSONObject json(Object... fields){JSONObject o=new JSONObject();try{for(int i=0;i<fields.length;i+=2)o.put((String)fields[i],fields[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return o;}
}
