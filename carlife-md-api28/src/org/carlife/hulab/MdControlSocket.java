package org.carlife.hulab;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Local control channel for on-box apps (abstract socket "@tbox_carlife_md").
 *
 * One line per connection, one line back. Commands:
 *   back_to_hu  ask the head unit to leave CarLife for its own UI (MSG_CMD_GO_TO_DESKTOP 0x10021),
 *               used by the launcher for the OEM "原车" app, whose own path (MainAiBox ->
 *               requestReverseCarPlayReturnNativeUI) only exists in CarPlay mode.
 * Replies: ok | no_session | unknown | error. Commands are harmless UI requests, so any local
 * caller is accepted; its uid is logged.
 */
final class MdControlSocket implements Runnable {
    static final String NAME="tbox_carlife_md";
    static final int MAX_LINE=64;

    static void start(){
        Thread t=new Thread(new MdControlSocket(),"md-control");
        t.setDaemon(true);
        t.start();
    }

    public void run(){
        LocalServerSocket server=null;
        while(server==null){
            try{server=new LocalServerSocket(NAME);}
            catch(IOException e){
                // A previous MdMain may still hold the name for a moment after a restart.
                MdMain.logFields("md_control_unavailable","socket","@"+NAME,"error",e.toString(),"retry_ms",5000);
                SystemClock.sleep(5000);
            }
        }
        MdMain.logFields("md_control_ready","socket","@"+NAME,"commands","back_to_hu");
        while(true){
            LocalSocket client=null;
            try{
                client=server.accept();
                handle(client);
            }catch(IOException e){
                MdMain.logFields("md_control_error","error",e.toString());
                SystemClock.sleep(200);
            }finally{
                if(client!=null)try{client.close();}catch(IOException ignored){}
            }
        }
    }

    private static void handle(LocalSocket client)throws IOException {
        client.setSoTimeout(1000);
        int uid=-1;
        try{Credentials c=client.getPeerCredentials();if(c!=null)uid=c.getUid();}catch(IOException ignored){}
        String command=readLine(client.getInputStream());
        String reply;
        if("back_to_hu".equals(command)){
            MdMain.Session s=MdMain.active;
            reply=s!=null&&s.requestBackToHu("control_socket uid="+uid)?"ok":"no_session";
            if(s==null)MdMain.logFields("back_to_hu_rejected","reason","no active CarLife session","caller_uid",uid);
        }else{
            reply="unknown";
            MdMain.logFields("md_control_unknown","command",command,"caller_uid",uid);
        }
        OutputStream out=client.getOutputStream();
        out.write((reply+"\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readLine(InputStream in)throws IOException {
        StringBuilder sb=new StringBuilder();
        int b;
        while(sb.length()<MAX_LINE&&(b=in.read())!=-1&&b!='\n')sb.append((char)b);
        return sb.toString().trim();
    }
}
