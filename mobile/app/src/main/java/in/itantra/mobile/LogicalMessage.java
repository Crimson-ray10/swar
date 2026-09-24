package in.itantra.mobile;
import org.json.*;
import java.util.UUID;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Same logical schema as backend/src/protocol/LogicalMessage.ts. */
public final class LogicalMessage {
    public static JSONObject from(ItpPacket.Decoded p,long timestamp){return Events.json("type","ITANTRA_MESSAGE","sessionId",p.session().toString(),"sequence",p.sequence(),"language",p.language(),"encoding","text_v1","payload",p.text(),"timestamp",timestamp);}
    public static ItpPacket.Decoded decode(JSONObject o)throws Exception{
        String text=o.getString("payload"),language=o.getString("language");long sequence=o.getLong("sequence");int size=text.getBytes(StandardCharsets.UTF_8).length;
        if(!o.getString("type").equals("ITANTRA_MESSAGE")||!o.getString("encoding").equals("text_v1")||sequence<1||sequence>9007199254740991L||size<1||size>ItpPacket.MAX_TEXT||o.getLong("timestamp")<0||!java.util.Set.of("hi","en","hinglish").contains(language))throw new IOException("Invalid logical message");
        return new ItpPacket.Decoded(UUID.fromString(o.getString("sessionId")),sequence,language,text,0,size,size);
    }
}
