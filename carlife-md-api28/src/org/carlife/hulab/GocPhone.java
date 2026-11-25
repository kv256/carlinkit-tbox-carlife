package org.carlife.hulab;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Head-unit phone keys for calls carried by the box's goc Bluetooth stack (sdsdk816).
 *
 * With the phone's calls connected to the box (persist.tbox.carlife.bt.phone=all), the HU's
 * steering-wheel / on-screen phone keys arrive as CarLife hard keys KEYCODE_PHONE_CALL (2),
 * KEYCODE_PHONE_END (3) and KEYCODE_PHONE_END_MUTE (4). They are turned into the documented
 * sdsdk816 commands (/system/config.ini) on its command pty: CE answer, CF reject, CG end call.
 * The HFP state comes from carlife-daemon.sh, which follows sdsdk816's MG indications
 * (1 not connected, 3 connected, 4 outgoing, 5 incoming, 6 talking) into STATE_FILE.
 */
final class GocPhone {
    static final String TTY="/dev/goc_serial";
    static final String STATE_FILE="/cache/carlife/bt.hfp";
    static final int KEY_PHONE_CALL=0x02, KEY_PHONE_END=0x03, KEY_PHONE_END_MUTE=0x04;
    static final int HFP_OUTGOING=4, HFP_INCOMING=5, HFP_TALKING=6;

    static boolean isPhoneKey(int key){return key==KEY_PHONE_CALL||key==KEY_PHONE_END||key==KEY_PHONE_END_MUTE;}

    /** -1 when unknown (daemon watcher not running yet or file unreadable). */
    static int hfpState(){
        try(BufferedReader r=new BufferedReader(new FileReader(STATE_FILE))){
            String s=r.readLine();
            return s==null?-1:Integer.parseInt(s.trim());
        }catch(IOException|NumberFormatException e){return -1;}
    }

    /** null = nothing to do for this key in this state. callAudio: the USB call path is recording. */
    static String commandFor(int key,int state,boolean callAudio){
        if(key==KEY_PHONE_CALL)return state==HFP_INCOMING?"CE":null;
        if(state==HFP_INCOMING)return "CF";
        if(state==HFP_OUTGOING||state==HFP_TALKING)return "CG";
        // State unknown but a call is clearly up on the USB path: ending it is the only sane reading.
        if(state<0&&callAudio)return "CG";
        return null;
    }

    static boolean send(String command){
        try(FileOutputStream out=new FileOutputStream(TTY)){
            out.write(("AT#"+command+"\r\n").getBytes(StandardCharsets.US_ASCII));
            return true;
        }catch(IOException e){
            MdMain.logFields("goc_command_failed","command",command,"error",e.toString());
            return false;
        }
    }
}
