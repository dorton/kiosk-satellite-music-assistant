package net.markandrew.kiosk.music;

import java.util.*;
import java.util.function.Consumer;
import org.junit.*;
import static org.junit.Assert.*;

/** Deterministic asynchronous host: every write/readback and audio-gate order is observable. */
public class LocalVolumeLinkTest {
    private final class Host implements LocalVolumeLink.Port {
        int master=100,writes;boolean gate=true,failWrite,failRead,reportOk=true,round;
        List<String> order=new ArrayList<>(),errors=new ArrayList<>();List<Integer> reports=new ArrayList<>();
        Deque<Runnable> callbacks=new ArrayDeque<>();List<Runnable> deadlines=new ArrayList<>();
        public void read(Consumer<Integer> done,Consumer<String> failed){order.add("read");callbacks.add(()->{if(failRead)failed.accept("read failed");else done.accept(master);});}
        public void write(int level,Runnable done,Consumer<String> failed){assertTrue("Only established group changes may avoid gating",gate||smooth||groupEnabled);writes++;order.add("write:"+level);callbacks.add(()->{master=round?(int)Math.round(level/10.0)*10:level;if(failWrite)failed.accept("uncertain");else done.run();});}
        public void gate(boolean held){gate=held;order.add("gate:"+held);}
        public boolean report(int level){reports.add(level);order.add("report:"+level);return reportOk;}
        int unmutes;
        boolean groupEnabled,groupFail;Integer groupResult;int groupCalls,groupDelta;
        Deque<Integer> groupResults=new ArrayDeque<>();List<Integer> groupDeltas=new ArrayList<>();
        boolean smooth;public boolean smoothGroupChange(){return smooth;}
        public boolean groupHardwareEnabled(){return groupEnabled;}
        List<String> routing=new ArrayList<>();public void groupRoute(String text){routing.add(text);}
        public void groupHardware(int previous,int actual,Consumer<Integer> done,Consumer<String> fail){groupCalls++;groupDelta=actual-previous;groupDeltas.add(groupDelta);Integer result=groupResults.isEmpty()?groupResult:groupResults.remove();callbacks.add(()->{if(groupFail)fail.accept("Uncertain group volume; no automatic retry.");else done.accept(result);});}
        public boolean reportHardware(int level,boolean unmute){if(unmute)unmutes++;return report(level);}
        public void later(Runnable task,long delay){deadlines.add(task);}
        public void error(String text){errors.add(text);}
        void one(){callbacks.remove().run();}
        void all(){int n=0;while(!callbacks.isEmpty()){assertTrue("No request feedback loop",n++<50);one();}}
    }
    private Host host;private LocalVolumeLink link;private long revision;
    @Before public void setup(){host=new Host();link=new LocalVolumeLink(host);}
    private void play(int volume){link.observe("verified-local-server-serial","playing",volume,++revision);host.all();}
    private void ma(int volume){link.observe("verified-local-server-serial","playing",volume,++revision);host.all();}
    private void stop(){link.observe("verified-local-server-serial","stopped",20,revision);host.all();}
    @Test public void startupUsesMaNotFullMasterAndWaitsForReadback(){link.observe("verified-local-server-serial","playing",20,++revision);assertTrue(host.gate);host.one();assertTrue(host.gate);host.one();assertEquals(20,host.master);assertTrue(host.gate);host.one();assertFalse(host.gate);assertEquals(Arrays.asList(20),host.reports);}
    @Test public void hardwareReportsActualVolumeWithoutSecondWrite(){play(20);int writes=host.writes;host.master=27;link.deviceChanged();host.all();assertEquals(writes,host.writes);assertEquals(Arrays.asList(20,27),host.reports);assertEquals(27,host.master);}
    @Test public void heldButtonBurstReportsFinalNativeMuteRatherThanOneFivePercentStep(){play(40);int writes=host.writes;host.reports.clear();host.master=33;link.deviceChanged();host.master=20;link.deviceChanged();host.master=7;link.deviceChanged();host.master=0;link.deviceChanged();host.all();assertEquals(writes,host.writes);assertEquals(Integer.valueOf(0),host.reports.get(host.reports.size()-1));assertEquals(0,host.master);}
    @Test public void maChangesMasterAndReadbackIsCanonical(){play(20);host.round=true;ma(33);assertEquals(30,host.master);assertEquals(Integer.valueOf(30),host.reports.get(host.reports.size()-1));assertFalse(host.gate);}
    @Test public void speechRestoresBaselineWithoutReportingIt(){play(20);host.reports.clear();link.speech(true);assertTrue(host.gate);host.all();assertEquals(100,host.master);assertTrue(host.gate);assertTrue(host.reports.isEmpty());link.speech(false);host.all();assertEquals(20,host.master);assertFalse(host.gate);assertFalse(host.reports.contains(100));}
    @Test public void speechAlreadyActiveAtStartupDoesNotUnmuteMusic(){link.speech(true);play(20);assertEquals(100,host.master);assertTrue(host.gate);assertTrue(host.reports.isEmpty());link.speech(false);host.all();assertEquals(20,host.master);assertFalse(host.gate);}
    @Test public void speechHardwareDoesNotBecomeMusicVolume(){play(20);link.speech(true);host.all();host.reports.clear();host.master=67;link.deviceChanged();host.all();assertTrue(host.reports.isEmpty());assertTrue(host.gate);link.speech(false);host.all();assertEquals(20,host.master);assertFalse(host.gate);}
    @Test public void maChangesDuringSpeechWaitForSpeechEnd(){play(20);link.speech(true);host.all();ma(35);assertEquals(100,host.master);assertTrue(host.gate);link.speech(false);host.all();assertEquals(35,host.master);assertFalse(host.gate);}
    @Test public void stopRestoresMasterWithoutReportingBaseline(){play(20);host.reports.clear();stop();assertEquals(100,host.master);assertTrue(host.gate);assertTrue(host.reports.isEmpty());assertFalse(link.ownsMaster());}
    @Test public void pauseDoesNotRestoreMaster(){play(20);link.observe("verified-local-server-serial","paused",20,revision);host.all();assertEquals(20,host.master);assertTrue(link.ownsMaster());}
    @Test public void noUiCloseInputIsRequiredToKeepSessionAlive(){play(20);link.observe("verified-local-server-serial","playing",20,revision);host.all();assertEquals(20,host.master);assertFalse(host.gate);}
    @Test public void disconnectRestoresOriginal(){play(20);link.observe("","stopped",null,-1);host.all();assertEquals(100,host.master);assertTrue(host.gate);assertFalse(link.ownsMaster());}
    @Test public void unknownStartupStaysSilentWithoutChangingMaster(){link.observe("verified","playing",null,1);host.all();assertTrue(host.gate);assertEquals(100,host.master);assertEquals(0,host.writes);}
    @Test public void zeroMaIsNotUnknown(){play(0);assertEquals(0,host.master);assertFalse(host.gate);}
    @Test public void originalNeedNotBeFull(){host.master=67;play(20);link.speech(true);host.all();assertEquals(67,host.master);stop();assertEquals(67,host.master);}
    @Test public void lateSpeechTransitionCannotUnmuteAtFullMaster(){play(20);link.speech(true);host.one();link.speech(false);assertEquals(100,host.master);host.one();assertTrue(host.gate);host.all();assertEquals(20,host.master);assertFalse(host.gate);}
    @Test public void staleMaWriteCannotUnmuteWrongLevel(){link.observe("verified-local-server-serial","playing",20,++revision);host.one();link.observe("verified-local-server-serial","playing",40,++revision);host.one();host.one();assertTrue(host.gate);host.all();assertEquals(40,host.master);assertFalse(host.gate);}
    @Test public void uncertainWriteStaysGatedAndDoesNotRetry(){host.failWrite=true;play(20);assertTrue(host.gate);assertEquals(1,host.writes);assertFalse(host.errors.isEmpty());ma(30);link.deviceChanged();host.all();assertEquals(1,host.writes);}
    @Test public void readFailureDoesNotSetFullVolume(){host.failRead=true;play(20);assertTrue(host.gate);assertEquals(0,host.writes);assertFalse(host.errors.isEmpty());}
    @Test public void timeoutIgnoresLateCompletion(){link.observe("verified","playing",20,1);host.deadlines.get(0).run();host.all();assertTrue(host.gate);assertEquals(0,host.writes);assertFalse(host.errors.isEmpty());}
    @Test public void closeMakesNoHostRestorationCall(){play(20);int writes=host.writes;link.close();assertTrue(host.gate);assertEquals(writes,host.writes);link.deviceChanged();link.speech(true);host.all();assertEquals(writes,host.writes);}
    @Test public void reportFailureFailsClosed(){host.reportOk=false;play(20);assertTrue(host.gate);assertFalse(host.errors.isEmpty());}
    @Test public void syntheticDeviceEventsDoNotReportSpeechMaster(){play(20);link.speech(true);link.deviceChanged();host.all();host.reports.clear();link.deviceChanged();host.all();assertTrue(host.reports.isEmpty());assertEquals(100,host.master);}
    @Test public void hardwareEventRacingStopStillRestoresBaseline(){play(100);host.master=67;stop();assertEquals(100,host.master);assertTrue(host.reports.isEmpty());}
    @Test public void nextSessionCapturesNewBaseline(){play(20);stop();host.master=80;play(30);stop();assertEquals(80,host.master);}
    @Test public void oldConnectionResultCannotUnmuteNewConnection(){play(20);link.speech(true);host.one();link.observe("new-server-serial","playing",30,1);host.one();assertTrue(host.gate);link.speech(false);host.all();assertEquals(30,host.master);assertFalse(host.gate);}
    private void mute(boolean value){link.observe("verified-local-server-serial","playing",20,revision,value);host.all();}
    @Test public void muteZerosMasterWithoutLosingMusicLevel(){play(20);host.reports.clear();mute(true);assertEquals(0,host.master);assertTrue(host.gate);assertTrue(host.reports.isEmpty());link.deviceChanged();host.all();assertTrue(host.reports.isEmpty());mute(false);assertEquals(20,host.master);assertFalse(host.gate);}
    @Test public void speechWhileMutedUsesOriginalThenReturnsToZero(){play(20);mute(true);host.reports.clear();link.speech(true);host.all();assertEquals(100,host.master);assertTrue(host.gate);link.speech(false);host.all();assertEquals(0,host.master);assertTrue(host.gate);assertTrue(host.reports.isEmpty());mute(false);assertEquals(20,host.master);}
    @Test public void stopWhileMutedRestoresOriginalNotMusicOrZero(){play(20);mute(true);host.reports.clear();link.observe("verified-local-server-serial","stopped",20,revision,true);host.all();assertEquals(100,host.master);assertTrue(host.reports.isEmpty());}
    @Test public void muteRacingWriteNeverReportsZeroAsMusic(){play(20);link.observe("verified-local-server-serial","playing",20,revision,true);host.one();link.observe("verified-local-server-serial","playing",20,revision,false);host.all();assertEquals(20,host.master);assertFalse(host.reports.contains(0));assertFalse(host.gate);}
    @Test public void uncertainMuteWriteNeverRetries(){play(20);host.failWrite=true;mute(true);int writes=host.writes;mute(false);link.deviceChanged();host.all();assertEquals(writes,host.writes);assertTrue(host.gate);assertFalse(host.errors.isEmpty());}
    @Test public void noIdentityDoesNotMuteExternalControllerDevice(){link.observe("","playing",20,1,true);host.all();assertEquals(100,host.master);assertEquals(0,host.writes);}
    @Test public void hardwareRaiseAfterControllerMuteUnmutesAndReportsActualWithoutAnotherWrite(){
        play(20);mute(true);int writes=host.writes;host.master=27;link.deviceChanged();host.all();
        assertEquals(1,host.unmutes);assertEquals(writes,host.writes);assertEquals(27,host.master);assertEquals(Integer.valueOf(27),host.reports.get(host.reports.size()-1));assertFalse(host.gate);
        link.observe("verified-local-server-serial","playing",27,++revision,false);host.all();assertEquals(writes,host.writes);assertFalse(host.gate);
    }
    @Test public void hardwareRaiseRacingMuteReadbackIsNotMistakenForAStuckMute(){
        play(20);link.observe("verified-local-server-serial","playing",20,revision,true);host.one();assertEquals(0,host.master);
        host.master=33;link.deviceChanged();host.all();assertEquals(33,host.master);assertEquals(1,host.unmutes);assertFalse(host.gate);assertTrue(host.errors.isEmpty());
    }
    @Test public void hardwareDuringSpeechCannotUnmuteControllerMute(){
        play(20);mute(true);link.speech(true);host.all();host.master=45;link.deviceChanged();host.all();assertEquals(0,host.unmutes);assertTrue(host.gate);link.speech(false);host.all();assertEquals(0,host.master);assertTrue(host.gate);
    }
    @Test public void failedLinkRequiresExplicitRecoveryNotReopenOrStateEcho(){
        play(20);host.failWrite=true;mute(true);int writes=host.writes;host.failWrite=false;
        mute(false);link.deviceChanged();host.all();assertEquals(writes,host.writes);assertTrue(host.gate);assertTrue(link.needsRecovery());
        link.recover();host.all();assertFalse(link.needsRecovery());assertFalse(host.gate);assertEquals(writes,host.writes);
    }
    @Test public void optionalGroupDoesNotReportAnIndividualVolumeChange(){play(20);host.reports.clear();host.groupEnabled=true;host.groupResult=24;host.master=27;link.deviceChanged();host.all();assertEquals(1,host.groupCalls);assertEquals(7,host.groupDelta);assertEquals(24,host.master);assertTrue(host.reports.isEmpty());assertFalse(host.gate);}
    @Test public void optionalGroupVerifiedSoloUsesExactlyTheOldPath(){play(20);host.reports.clear();host.groupEnabled=true;host.master=27;link.deviceChanged();host.all();assertEquals(1,host.groupCalls);assertEquals(Arrays.asList(27),host.reports);assertEquals(27,host.master);}
    @Test public void optionOffNeverInvokesGroupRouting(){play(20);host.reports.clear();host.master=27;link.deviceChanged();host.all();assertEquals(0,host.groupCalls);assertEquals(Arrays.asList(27),host.reports);}
    @Test public void establishedDirectGroupUpdateDoesNotGateOrEchoIndividualVolume(){play(20);host.smooth=true;host.order.clear();host.reports.clear();ma(35);assertFalse(host.order.contains("gate:true"));assertTrue(host.reports.isEmpty());assertEquals(35,host.master);}
    @Test public void directGroupStillGatesSpeechAndRestoresOriginalMaster(){play(20);host.smooth=true;host.order.clear();link.speech(true);host.all();assertTrue(host.order.contains("gate:true"));assertEquals(100,host.master);assertTrue(host.gate);}
    @Test public void optionalGroupSpeechHardwareNeverRoutesToGroup(){play(20);host.groupEnabled=true;link.speech(true);host.all();host.master=67;link.deviceChanged();host.all();assertEquals(0,host.groupCalls);link.speech(false);host.all();assertEquals(20,host.master);}
    @Test public void optionalGroupFailureStaysGatedWithoutRetryOrIndividualFallback(){play(20);host.groupEnabled=true;host.groupFail=true;host.reports.clear();host.master=27;link.deviceChanged();host.all();link.deviceChanged();host.all();assertEquals(1,host.groupCalls);assertTrue(host.reports.isEmpty());assertTrue(host.gate);assertTrue(link.needsRecovery());}
    @Test public void optionalGroupAtNativeZeroCanStillLowerOtherMembers(){play(0);host.groupEnabled=true;host.groupResult=0;host.reports.clear();link.edgeKey(-1,7,link.edgeStamp(-1));host.all();assertEquals(1,host.groupCalls);assertEquals(-7,host.groupDelta);assertTrue(host.reports.isEmpty());assertEquals(0,host.master);}
    @Test public void edgeFallbackDoesNotDoubleAnObservedNativeDelta(){play(0);host.groupEnabled=true;long stamp=link.edgeStamp(-1);host.master=7;host.groupResult=10;link.deviceChanged();host.all();link.edgeKey(-1,7,stamp);host.all();assertEquals(1,host.groupCalls);}
    @Test public void edgeFallbackForStandaloneDoesNotReportAnInvalidVolume(){play(0);host.groupEnabled=true;host.reports.clear();link.edgeKey(-1,7,link.edgeStamp(-1));host.all();assertEquals(1,host.groupCalls);assertTrue(host.reports.isEmpty());assertEquals(0,host.master);}
    @Test public void groupEchoDuringRoutingDoesNotReportAnIndividualAdjustment(){play(20);host.groupEnabled=true;host.groupResult=24;host.reports.clear();host.master=27;link.deviceChanged();host.one();link.observe("verified-local-server-serial","playing",24,++revision);host.all();assertEquals(24,host.master);assertTrue(host.reports.isEmpty());assertFalse(host.gate);}
    @Test public void heldNativeChangesDuringGroupRequestAreNotOverwritten(){play(40);host.groupEnabled=true;host.groupResult=30;host.reports.clear();host.master=33;link.deviceChanged();host.one();host.master=20;link.deviceChanged();host.all();assertEquals(2,host.groupCalls);assertEquals(-13,host.groupDelta);assertTrue(host.reports.isEmpty());assertFalse(host.gate);}
    @Test public void speechDoesNotWaitForAGroupNetworkRequestToRestoreMaster(){play(20);host.groupEnabled=true;host.groupResult=24;host.reports.clear();host.master=27;link.deviceChanged();host.one();link.speech(true);host.all();assertEquals(100,host.master);assertTrue(host.gate);assertTrue(host.reports.isEmpty());link.speech(false);host.all();assertEquals(20,host.master);assertFalse(host.gate);}
    @Test public void groupDeltaAndReadbackDoNotDeliberatelySilencePlayingMusic(){play(20);host.groupEnabled=true;host.groupResult=24;host.reports.clear();host.order.clear();host.master=27;link.deviceChanged();host.one();assertFalse(host.gate);host.all();assertFalse(host.order.contains("gate:true"));assertTrue(host.reports.isEmpty());assertEquals(24,host.master);assertTrue(host.routing.get(0).contains("Group volume verified"));}
    @Test public void groupEchoDuringRequestDoesNotGateMusic(){play(20);host.groupEnabled=true;host.groupResult=24;host.order.clear();host.master=27;link.deviceChanged();host.one();link.observe("verified-local-server-serial","playing",24,++revision);host.all();assertFalse(host.order.contains("gate:true"));}
    @Test public void ungatedGroupReadbackFailureStillFailsClosedWithoutRetry(){play(20);host.groupEnabled=true;host.groupResult=24;host.reports.clear();host.master=27;link.deviceChanged();host.one();host.one();host.failWrite=true;host.all();assertTrue(host.gate);assertTrue(link.needsRecovery());int calls=host.groupCalls;link.deviceChanged();host.all();assertEquals(calls,host.groupCalls);assertTrue(host.reports.isEmpty());}
    @Test public void noOpVolumeNotificationAtZeroDoesNotConsumeEndpointPress(){play(0);host.groupEnabled=true;host.groupResult=0;long stamp=link.edgeStamp(-1);link.deviceChanged();host.all();link.edgeKey(-1,7,stamp);host.all();assertEquals(1,host.groupCalls);assertEquals(-7,host.groupDelta);}
    @Test public void noOpVolumeNotificationAtHundredDoesNotConsumeEndpointPress(){play(100);host.groupEnabled=true;host.groupResult=100;long stamp=link.edgeStamp(1);link.deviceChanged();host.all();link.edgeKey(1,7,stamp);host.all();assertEquals(1,host.groupCalls);assertEquals(7,host.groupDelta);}
    @Test public void endpointPressWaitsForNoOpReadWithoutRetryingACommand(){play(0);host.groupEnabled=true;host.groupResult=0;long stamp=link.edgeStamp(-1);link.deviceChanged();link.edgeKey(-1,7,stamp);Runnable wait=host.deadlines.get(host.deadlines.size()-1);host.all();wait.run();host.all();assertEquals(1,host.groupCalls);}
    @Test public void theSameEndpointPressCannotSubmitTwice(){play(0);host.groupEnabled=true;host.groupResult=0;long stamp=link.edgeStamp(-1);link.edgeKey(-1,7,stamp);host.all();link.edgeKey(-1,7,stamp);host.all();assertEquals(1,host.groupCalls);}
    @Test public void speechInvalidatesAnAlreadyScheduledEndpointPress(){play(0);host.groupEnabled=true;long stamp=link.edgeStamp(-1);link.speech(true);host.all();link.speech(false);host.all();link.edgeKey(-1,7,stamp);host.all();assertEquals(0,host.groupCalls);}
    @Test public void rapidPressesBeforeNativeReadAreOneNetGroupDelta(){
        play(40);host.groupEnabled=true;host.groupResult=19;host.reports.clear();
        host.master=33;link.deviceChanged();host.master=26;link.deviceChanged();host.master=19;link.deviceChanged();host.all();
        assertEquals(Arrays.asList(-21),host.groupDeltas);assertEquals(19,host.master);assertTrue(host.reports.isEmpty());assertFalse(link.needsRecovery());
    }
    @Test public void rapidPressesDuringReadbackPreserveRemainingDeltaWithoutReplayingFirstPress(){
        play(40);host.groupEnabled=true;host.groupResults.addAll(Arrays.asList(33,19));host.reports.clear();
        host.master=33;link.deviceChanged();host.one();
        host.master=26;link.deviceChanged();host.master=19;link.deviceChanged();host.all();
        assertEquals(Arrays.asList(-7,-14),host.groupDeltas);assertEquals(19,host.master);assertTrue(host.reports.isEmpty());assertFalse(link.needsRecovery());
    }
    @Test public void serverEchoDuringRapidPressesDoesNotBecomeAnotherHardwareStep(){
        play(40);host.groupEnabled=true;host.groupResults.addAll(Arrays.asList(33,19));host.reports.clear();
        host.master=33;link.deviceChanged();host.one();
        link.observe("verified-local-server-serial","playing",33,++revision);
        host.master=26;link.deviceChanged();host.master=19;link.deviceChanged();host.all();
        assertEquals(Arrays.asList(-7,-14),host.groupDeltas);assertEquals(19,host.master);assertTrue(host.reports.isEmpty());assertFalse(link.needsRecovery());
    }
    @Test public void reversingDirectionDuringReadbackUsesNetNativeMovement(){
        play(40);host.groupEnabled=true;host.groupResults.addAll(Arrays.asList(33,47));host.reports.clear();
        host.master=33;link.deviceChanged();host.one();
        host.master=40;link.deviceChanged();host.master=47;link.deviceChanged();host.all();
        assertEquals(Arrays.asList(-7,14),host.groupDeltas);assertEquals(47,host.master);assertTrue(host.reports.isEmpty());assertFalse(link.needsRecovery());
    }
    @Test public void oppositePressesThatCancelDuringReadbackDoNotAddAGroupWrite(){
        play(40);host.groupEnabled=true;host.groupResult=33;host.reports.clear();
        host.master=33;link.deviceChanged();host.one();
        host.master=26;link.deviceChanged();host.master=33;link.deviceChanged();host.all();
        assertEquals(Arrays.asList(-7),host.groupDeltas);assertEquals(33,host.master);assertTrue(host.reports.isEmpty());assertFalse(link.needsRecovery());
    }
    @Test public void failedReadbackDiscardsPendingBurstAndNeverRetriesOrFallsBack(){
        play(40);host.groupEnabled=true;host.groupFail=true;host.reports.clear();
        host.master=33;link.deviceChanged();host.one();
        host.master=26;link.deviceChanged();host.master=19;link.deviceChanged();host.all();link.deviceChanged();host.all();
        assertEquals(Arrays.asList(-7),host.groupDeltas);assertTrue(host.reports.isEmpty());assertTrue(link.needsRecovery());assertTrue(host.gate);
    }
}
