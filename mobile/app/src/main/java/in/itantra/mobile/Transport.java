package in.itantra.mobile;
import org.json.JSONObject;

/** Presentation adapter. Acoustic implementations have no dependency on a network implementation. */
public interface Transport extends AutoCloseable {
    interface Listener { void event(String type,JSONObject data); void received(ItpPacket.Decoded message); }
    void host(); void discover(); void connect(String address,int port,String pin,String callsign);
    void respondRequest(boolean accept); void cancelRequest(); void callsign(String name);
    void sendText(String text,String language); void bitrate(int bps); boolean isConnected();
    void disconnect(); void close();
    default void start(){discover();} default void stop(){disconnect();}
    default void pause(){disconnect();}
    default String getState(){return isConnected()?"CONNECTED":"DISCONNECTED";}
    default JSONObject getMetrics(){return Events.json("transport","WIFI_LAN");}
}
