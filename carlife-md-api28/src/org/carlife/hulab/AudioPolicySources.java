package org.carlife.hulab;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.EnumMap;

/** One privileged Android policy, disjoint media/navigation/assistant sinks. No permission changes. */
final class AudioPolicySources {
    interface Platform {
        void register() throws Exception;
        PcmAudioSession.Source open(CarLifeAudio.Lane lane) throws Exception;
        void unregister() throws Exception;
    }
    interface PlatformFactory {Platform create() throws Exception;}
    private final PlatformFactory factory;
    private final SourceLease global;
    private final EnumMap<CarLifeAudio.Lane,OwnedSource> active=new EnumMap<CarLifeAudio.Lane,OwnedSource>(CarLifeAudio.Lane.class);
    private final EnumMap<CarLifeAudio.Lane,SourceLease> laneLeases=new EnumMap<CarLifeAudio.Lane,SourceLease>(CarLifeAudio.Lane.class);
    private Platform platform;
    private boolean leased,poisoned;
    private long registrations,releases,failures;private String lastFailure;
    AudioPolicySources(){this(new PlatformFactory(){public Platform create(){return new AndroidPlatform();}},PcmAudioSession.LEASE);}
    AudioPolicySources(PlatformFactory factory,SourceLease global){
        if(factory==null||global==null)throw new NullPointerException("audio policy owner");
        this.factory=factory;this.global=global;
        for(CarLifeAudio.Lane lane:CarLifeAudio.Lane.values())laneLeases.put(lane,new SourceLease());
    }
    SourceLease lease(CarLifeAudio.Lane lane){return laneLeases.get(lane);}
    PcmAudioSession.Factory factory(final CarLifeAudio.Lane lane){
        if(lane==null)throw new NullPointerException("audio lane");
        return new PcmAudioSession.Factory(){public PcmAudioSession.Source create(){return new OwnedSource(lane);}};
    }
    synchronized boolean retired(){return active.isEmpty()&&platform==null&&!leased;}
    synchronized String diagnostics(){return "routes="+active.keySet()+" registrations="+registrations+" releases="+releases+" cleanup_unconfirmed="+poisoned+" failures="+failures+" last_failure="+lastFailure;}
    private synchronized PcmAudioSession.Source attach(OwnedSource source)throws Exception{
        if(poisoned)throw new IOException("previous audio policy cleanup unconfirmed");
        if(active.containsKey(source.lane))throw new IOException("duplicate audio sink "+source.lane);
        if(platform==null){
            global.acquire(this,"split Android audio policy");leased=true;
            try{
                platform=factory.create();if(platform==null)throw new IOException("null audio policy platform");
                platform.register();registrations++;
            }catch(Exception|LinkageError error){
                Exception failure=exception(error);
                failures++;lastFailure=failure.toString();
                try{closePolicy();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        try{
            PcmAudioSession.Source sink=platform.open(source.lane);
            if(sink==null)throw new IOException("audio policy returned null "+source.lane+" sink");
            active.put(source.lane,source);source.attached=true;return sink;
        }catch(Exception|LinkageError error){
            Exception failure=exception(error);
            failures++;lastFailure=failure.toString();
            // Failure opening a lane leaves an already-running other lane intact.
            if(active.isEmpty())try{closePolicy();}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private synchronized void detach(OwnedSource source,Exception captureFailure)throws Exception{
        if(!source.attached)return;
        source.attached=false;
        if(active.get(source.lane)!=source)throw new IOException("audio policy ownership mismatch");
        active.remove(source.lane);
        if(captureFailure!=null){poisoned=true;failures++;lastFailure=captureFailure.toString();}
        if(active.isEmpty()){
            try{closePolicy();}catch(Exception cleanup){if(captureFailure!=null)cleanup.addSuppressed(captureFailure);throw cleanup;}
            // Synchronous policy removal invalidates all its captors, including
            // a record whose individual release failed.
            return;
        }
        if(captureFailure!=null)throw captureFailure;
    }
    private void closePolicy()throws Exception{
        if(!leased)return;
        try{
            if(platform!=null)platform.unregister();
            platform=null;poisoned=false;leased=false;releases++;global.release(this,true);
        }catch(Exception|LinkageError error){
            Exception failure=exception(error);poisoned=true;failures++;lastFailure=failure.toString();global.release(this,false);throw failure;}
    }
    private static Exception exception(Throwable error){return error instanceof Exception?(Exception)error:new IOException("audio policy linkage failure",error);}
    private final class OwnedSource implements PcmAudioSession.Source {
        final CarLifeAudio.Lane lane;volatile PcmAudioSession.Source sink;boolean attached;
        OwnedSource(CarLifeAudio.Lane lane){this.lane=lane;}
        public void start()throws Exception{sink=attach(this);sink.start();}
        public int read(byte[] data,int offset,int size)throws Exception{return sink.read(data,offset,size);}
        public void abort(){PcmAudioSession.Source s=sink;if(s!=null)s.abort();}
        public void close()throws Exception{
            Exception failure=null;
            if(sink!=null)try{sink.close();}catch(Exception|LinkageError e){failure=exception(e);}finally{sink=null;}
            detach(this,failure);
        }
    }

    /** Hidden platform APIs are reflected; their normal registration permission checks remain. */
    static final class AndroidPlatform implements Platform {
        private Object policy,manager,ttsUidMix;private Class<?> policyType,mixType;
        private final EnumMap<CarLifeAudio.Lane,Object> mixes=new EnumMap<CarLifeAudio.Lane,Object>(CarLifeAudio.Lane.class);
        private HandlerThread thread;
        private boolean registrationAttempted;
        public void register()throws Exception{
            policyType=Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> policyBuilderType=Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            mixType=Class.forName("android.media.audiopolicy.AudioMix");
            Context ctx=null;
            try{ctx=AndroidRuntimeContext.get();}catch(Exception ignored){}
            Object builder=policyBuilderType.getConstructor(Context.class).newInstance(new Object[]{ctx});
            thread=new HandlerThread("carlife-audio-policy");thread.start();
            invoke(builder,"setLooper",new Class<?>[]{Looper.class},thread.getLooper());
            int[] naviUids=AudioUsage.naviUids();
            ttsUidMix=null;
            for(CarLifeAudio.Lane lane:CarLifeAudio.Lane.values()){
                if(AudioUsage.forLane(lane).length==0)continue;
                Object mix=buildMix(lane,lane==CarLifeAudio.Lane.MEDIA?naviUids:null);
                mixes.put(lane,mix);
                invoke(builder,"addMix",new Class<?>[]{mixType},mix);
                if(lane==CarLifeAudio.Lane.TTS&&naviUids.length>0){
                    ttsUidMix=buildUidMix(lane,naviUids);
                    invoke(builder,"addMix",new Class<?>[]{mixType},ttsUidMix);
                }
            }
            if(mixes.isEmpty())throw new IOException("no USB playback lanes enabled");
            policy=invoke(builder,"build",new Class<?>[0]);
            manager=ctx!=null?ctx.getSystemService(Context.AUDIO_SERVICE):AudioManager.class.getConstructor().newInstance();
            registrationAttempted=true;
            int result=((Integer)invoke(manager,"registerAudioPolicy",new Class<?>[]{policyType},policy)).intValue();
            if(result!=0)throw new IOException("registerAudioPolicy failed: "+result+"; existing MODIFY_AUDIO_ROUTING required");
        }
        private Object buildMix(CarLifeAudio.Lane lane,int[] excludeUids)throws Exception{
            Class<?> ruleType=Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> rbType=Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Class<?> mbType=Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object rb=rbType.getConstructor().newInstance();
            int match=ruleType.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
            try{
                int role=ruleType.getField("MIX_ROLE_PLAYERS").getInt(null);
                invoke(rb,"setTargetMixRole",new Class<?>[]{int.class},role);
            }catch(NoSuchFieldException|NoSuchMethodException api28){
                // API 28 infers MIX_TYPE_PLAYERS automatically from RULE_MATCH_ATTRIBUTE_USAGE.
            }
            for(int usage:AudioUsage.forLane(lane)){
                AudioAttributes attr=new AudioAttributes.Builder().setUsage(usage).build();
                invoke(rb,"addRule",new Class<?>[]{AudioAttributes.class,int.class},attr,match);
            }
            if(excludeUids!=null&&excludeUids.length>0){
                int matchUid=ruleType.getField("RULE_MATCH_UID").getInt(null);
                for(int uid:excludeUids){
                    invoke(rb,"excludeMixRule",new Class<?>[]{int.class,Object.class},Integer.valueOf(matchUid),Integer.valueOf(uid));
                }
            }
            Object rule=invoke(rb,"build",new Class<?>[0]);
            Object mb=mbType.getConstructor(ruleType).newInstance(rule);
            // Output channel masks are required here; createAudioRecordSink converts them to input masks.
            AudioFormat format=new AudioFormat.Builder().setSampleRate(lane.rate)
                .setChannelMask(lane.channels==2?AudioFormat.CHANNEL_OUT_STEREO:AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            invoke(mb,"setFormat",new Class<?>[]{AudioFormat.class},format);
            invoke(mb,"setRouteFlags",new Class<?>[]{int.class},mixType.getField("ROUTE_FLAG_LOOP_BACK").getInt(null));
            return invoke(mb,"build",new Class<?>[0]);
        }
        private Object buildUidMix(CarLifeAudio.Lane lane,int[] matchUids)throws Exception{
            Class<?> ruleType=Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> rbType=Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Class<?> mbType=Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object rb=rbType.getConstructor().newInstance();
            int matchUid=ruleType.getField("RULE_MATCH_UID").getInt(null);
            for(int uid:matchUids){
                invoke(rb,"addMixRule",new Class<?>[]{int.class,Object.class},Integer.valueOf(matchUid),Integer.valueOf(uid));
            }
            Object rule=invoke(rb,"build",new Class<?>[0]);
            Object mb=mbType.getConstructor(ruleType).newInstance(rule);
            AudioFormat format=new AudioFormat.Builder().setSampleRate(lane.rate)
                .setChannelMask(lane.channels==2?AudioFormat.CHANNEL_OUT_STEREO:AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            invoke(mb,"setFormat",new Class<?>[]{AudioFormat.class},format);
            invoke(mb,"setRouteFlags",new Class<?>[]{int.class},mixType.getField("ROUTE_FLAG_LOOP_BACK").getInt(null));
            return invoke(mb,"build",new Class<?>[0]);
        }
        public PcmAudioSession.Source open(CarLifeAudio.Lane lane)throws Exception{
            if(policy==null)throw new IOException("audio policy not registered");
            Object mix=mixes.get(lane);
            if(mix==null)throw new IOException("audio policy has no mix for "+lane);
            AudioRecord record=(AudioRecord)invoke(policy,"createAudioRecordSink",new Class<?>[]{mixType},mix);
            if(record==null)throw new IOException("createAudioRecordSink returned null for "+lane);
            if(lane==CarLifeAudio.Lane.TTS&&ttsUidMix!=null){
                AudioRecord uidRecord=null;
                try{
                    uidRecord=(AudioRecord)invoke(policy,"createAudioRecordSink",new Class<?>[]{mixType},ttsUidMix);
                }catch(Exception e){
                    record.release();
                    throw e;
                }
                if(uidRecord==null){
                    record.release();
                    throw new IOException("createAudioRecordSink returned null for TTS UID mix");
                }
                return new DualPolicyRecord(uidRecord,record,lane);
            }
            return new PolicyRecord(record,lane);
        }
        public void unregister()throws Exception{
            if(registrationAttempted&&policy!=null&&manager!=null){
                try{
                    invoke(manager,"unregisterAudioPolicy",new Class<?>[]{policyType},policy);
                }catch(NoSuchMethodException api28){
                    invoke(manager,"unregisterAudioPolicyAsync",new Class<?>[]{policyType},policy);
                    try{Thread.sleep(150);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                }
                registrationAttempted=false;
            }
            policy=null;manager=null;ttsUidMix=null;mixes.clear();
            if(thread!=null){HandlerThread t=thread;thread=null;t.quitSafely();boolean interrupted=Thread.interrupted();long deadline=System.nanoTime()+1000000000L;
                try{while(t.isAlive()&&System.nanoTime()<deadline){try{t.join(Math.max(1,(deadline-System.nanoTime())/1000000L));}catch(InterruptedException e){interrupted=true;}}}
                finally{if(interrupted)Thread.currentThread().interrupt();}
                if(t.isAlive())throw new IOException("audio policy callback thread did not retire");}
        }
        private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception{
            Method method=target.getClass().getMethod(name,types);
            try{return method.invoke(target,args);}catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException("audio policy API "+name+" failed",cause);}
        }
    }
    private static final class PolicyRecord implements PcmAudioSession.Source {
        private volatile AudioRecord record;private final CarLifeAudio.Lane lane;private volatile boolean aborted;
        PolicyRecord(AudioRecord record,CarLifeAudio.Lane lane){this.record=record;this.lane=lane;}
        public void start()throws IOException{
            if(record.getState()!=AudioRecord.STATE_INITIALIZED||record.getSampleRate()!=lane.rate||record.getChannelCount()!=lane.channels||record.getAudioFormat()!=AudioFormat.ENCODING_PCM_16BIT)
                throw new IOException("AudioMix actual format/state mismatch for "+lane);
            record.startRecording();
            if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("AudioMix recording did not start for "+lane);
        }
        public int read(byte[] data,int offset,int size)throws IOException{
            AudioRecord r=record;
            if(r==null||aborted)return 0;
            int n=r.read(data,offset,size,AudioRecord.READ_BLOCKING);
            if(aborted&&n<=0)return 0;
            if(n<0)throw new IOException("AudioMix read failed for "+lane+": "+n);
            return n;
        }
        public void abort(){
            aborted=true;
            AudioRecord r=record;
            if(r!=null)try{r.stop();}catch(RuntimeException ignored){}
        }
        public void close(){aborted=true;AudioRecord r=record;record=null;if(r!=null)r.release();}
    }
    /**
     * Mixes two same-format r_submix AudioRecord sinks (navi-app UID mix + USAGE_NAVI mix)
     * at exact 1x real-time pace without double-speed interleaving.
     */
    private static final class DualPolicyRecord implements PcmAudioSession.Source {
        private AudioRecord recA,recB;
        private final CarLifeAudio.Lane lane;
        private final byte[] bufA=new byte[2048],bufB=new byte[2048];
        private int usedA,usedB;
        private long singleSideSinceMs;
        DualPolicyRecord(AudioRecord a,AudioRecord b,CarLifeAudio.Lane lane){this.recA=a;this.recB=b;this.lane=lane;}
        public void start()throws IOException{
            validateAndStart(recA,"TTS_UID");
            validateAndStart(recB,"TTS_USAGE");
        }
        private void validateAndStart(AudioRecord r,String tag)throws IOException{
            if(r.getState()!=AudioRecord.STATE_INITIALIZED||r.getSampleRate()!=lane.rate||r.getChannelCount()!=lane.channels||r.getAudioFormat()!=AudioFormat.ENCODING_PCM_16BIT)
                throw new IOException("AudioMix actual format/state mismatch for "+tag);
            r.startRecording();
            if(r.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("AudioMix recording did not start for "+tag);
        }
        public int read(byte[] data,int offset,int size)throws IOException{
            int cap=Math.min(size,bufA.length);
            if(usedA<cap){
                int nA=recA.read(bufA,usedA,cap-usedA,AudioRecord.READ_NON_BLOCKING);
                if(nA<0)throw new IOException("AudioMix read failed for TTS_UID: "+nA);
                usedA+=nA;
            }
            if(usedB<cap){
                int nB=recB.read(bufB,usedB,cap-usedB,AudioRecord.READ_NON_BLOCKING);
                if(nB<0)throw new IOException("AudioMix read failed for TTS_USAGE: "+nB);
                usedB+=nB;
            }
            int ready=Math.min(usedA,usedB)&~1;
            if(ready>0){
                singleSideSinceMs=0;
                for(int i=0;i<ready;i+=2){
                    int sA=(short)((bufA[i]&0xff)|((bufA[i+1]&0xff)<<8));
                    int sB=(short)((bufB[i]&0xff)|((bufB[i+1]&0xff)<<8));
                    int sum=sA+sB;
                    if(sum>32767)sum=32767;else if(sum<-32768)sum=-32768;
                    data[offset+i]=(byte)(sum&0xff);
                    data[offset+i+1]=(byte)((sum>>>8)&0xff);
                }
                consume(bufA,usedA,ready);usedA-=ready;
                consume(bufB,usedB,ready);usedB-=ready;
                return ready;
            }
            // Fallback if one r_submix sink ever stalls silent while the other streams continuously.
            if(usedA>=2||usedB>=2){
                long now=SystemClock.uptimeMillis();
                if(singleSideSinceMs==0)singleSideSinceMs=now;
                else if(now-singleSideSinceMs>=40){
                    if(usedA>=2&&usedB<2){
                        int n=Math.min(usedA,size)&~1;
                        System.arraycopy(bufA,0,data,offset,n);
                        consume(bufA,usedA,n);usedA-=n;
                        return n;
                    }
                    if(usedB>=2&&usedA<2){
                        int n=Math.min(usedB,size)&~1;
                        System.arraycopy(bufB,0,data,offset,n);
                        consume(bufB,usedB,n);usedB-=n;
                        return n;
                    }
                }
            }
            return 0;
        }
        private static void consume(byte[] b,int used,int n){
            int rem=used-n;
            if(rem>0)System.arraycopy(b,n,b,0,rem);
        }
        public void close(){
            if(recA!=null){try{recA.release();}finally{recA=null;}}
            if(recB!=null){try{recB.release();}finally{recB=null;}}
        }
    }
}
