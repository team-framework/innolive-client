package com.framework.innolive.feature.live.privacy

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.preferredHardwareVideoCodecs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host ICE only: protected GPU frames pass through real RTP encoding and decoding locally. */
@RunWith(AndroidJUnit4::class)
class PrivacyLocalPeerEncoderDeviceTest {
    @Test fun preferredHardwareH264DeliversProtectedTextureToLocalPeer() { runPeer(true) }
    @Test fun compareDefaultCodecWithHardwarePreference() {
        val baseline=runPeer(false,1920,1080,true);val preferred=runPeer(true,1920,1080,true)
        Log.i("PrivacyEncoder","codec_comparison baseline_codec=${baseline.codec} candidate_codec=${preferred.codec} " +
            "baseline_encode_mean_ms=${baseline.encodeMeanMs} candidate_encode_mean_ms=${preferred.encodeMeanMs} " +
            "baseline_cpu_readbacks=${baseline.readbacks} candidate_cpu_readbacks=${preferred.readbacks} " +
            "baseline_encoded_fps=${baseline.encodedFps} candidate_encoded_fps=${preferred.encodedFps} size=1920x1080 detector=true")
    }
    @Test fun uprightRotatedProtectedTextureStaysOnGpuWithProductionSourceAdaptation() {
        val rotated=runPeer(true,1920,1080,false,adaptOutput=true,rotation=90,outputUpright=true)
        assertEquals("Local RTP converted rotated protected frames to I420",0L,rotated.readbacks)
    }
    private data class Metrics(val codec:String?,val encodeMeanMs:Double,val readbacks:Long,val encodedFps:Double)
    private fun runPeer(preferHardware:Boolean,width:Int=320,height:Int=320,inference:Boolean=false,
                        adaptOutput:Boolean=false,rotation:Int=0,outputUpright:Boolean=false):Metrics {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl=EglBase.create()
        val factory=PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext,true,true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val received=CountDownLatch(3)
        val source=factory.createVideoSource(false)
        if(adaptOutput) source.adaptOutputFormat(width,height,30)
        val track=factory.createVideoTrack("local-protected-video",source)
        val send=Peer(factory,null)
        val receive=Peer(factory,received)
        val fixture=JavaI420Buffer.allocate(width,height)
        repeat(fixture.dataY.capacity()) { fixture.dataY.put(it,(16+(it%width*13+it/width*31)%220).toByte()) }
        repeat(fixture.dataU.capacity()) { fixture.dataU.put(it,128.toByte());fixture.dataV.put(it,128.toByte()) }
        try {
            val transceiver=checkNotNull(send.connection.addTransceiver(track,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)))
            if(preferHardware) {
                val preferred=checkNotNull(preferredHardwareVideoCodecs(
                    factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs,
                    HardwareVideoEncoderFactory(egl.eglBaseContext,true,true).supportedCodecs.toList()))
                assertTrue(transceiver.setCodecPreferences(preferred).isSuccess())
            }
            send.local(send.create(true))
            assertTrue("Offer ICE gathering did not complete",send.gathered.await(5,TimeUnit.SECONDS))
            receive.remote(checkNotNull(send.connection.localDescription))
            receive.local(receive.create(false))
            assertTrue("Answer ICE gathering did not complete",receive.gathered.await(5,TimeUnit.SECONDS))
            send.remote(checkNotNull(receive.connection.localDescription))
            assertTrue("Local sender did not connect",send.connected.await(8,TimeUnit.SECONDS))
            assertTrue("Local receiver did not connect",receive.connected.await(8,TimeUnit.SECONDS))
            if(width>320) assertTrue(send.connection.setBitrate(3_000_000,6_000_000,6_000_000))
            source.capturerObserver.onCapturerStarted(true)
            PrivacyGpuFramePipeline(egl.eglBaseContext,useGles3=true,useFence=true).use { graph ->
                if(inference)graph.createNativeInputModel(PrivacyDetectorGpuEngine.verifiedFile(context).absolutePath)
                val predictions=java.nio.ByteBuffer.allocateDirect(38*8400*4)
                val prototypes=java.nio.ByteBuffer.allocateDirect(32*160*160*4)
                val readbacks=PrivacyTextureReadbackCounter.value()
                val started=System.nanoTime()
                repeat(60) { index ->
                    val layout=graph.prepare(fixture,rotation,readModel=false)
                    if(inference)graph.predictNativeInputInto(predictions,prototypes)
                    val texture=graph.finish(ByteArray(160*160) {-1},layout,width,height,
                        outputUpright=outputUpright)
                    val frame=VideoFrame(texture,if(outputUpright) 0 else rotation,System.nanoTime())
                    try {source.capturerObserver.onFrameCaptured(frame)} finally {frame.release()}
                    val remaining=started+(index+1)*33_333_333L-System.nanoTime()
                    if(remaining>0)Thread.sleep(remaining/1_000_000L,(remaining%1_000_000L).toInt())
                }
                val seconds=(System.nanoTime()-started)/1e9
                assertTrue("No decoded protected frames",received.await(5,TimeUnit.SECONDS))
                val ready=CountDownLatch(1)
                var codec:String?=null;var implementation:String?=null;var packets=0L
                var encodeMs=Double.NaN
                var encodedFrames=0.0
                send.connection.getStats { report ->
                    val outbound=report.statsMap.values.firstOrNull {
                        it.type=="outbound-rtp" && (it.members["kind"]=="video" || it.members["mediaType"]=="video")
                    }
                    outbound?.let {
                        implementation=it.members["encoderImplementation"] as? String
                        packets=(it.members["packetsSent"] as? Number)?.toLong() ?: 0
                        codec=report.statsMap[it.members["codecId"]]?.members?.get("mimeType") as? String
                        val frames=(it.members["framesEncoded"] as? Number)?.toDouble() ?: 0.0
                        encodedFrames=frames
                        val seconds=(it.members["totalEncodeTime"] as? Number)?.toDouble()
                        if(frames>0 && seconds!=null)encodeMs=seconds*1000/frames
                    }
                    ready.countDown()
                }
                assertTrue(ready.await(5,TimeUnit.SECONDS))
                assertTrue("No RTP video packets sent",packets>0)
                if(preferHardware) {
                    assertEquals("video/H264",codec)
                    assertTrue("Software encoder was selected: $implementation",
                        android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.any {
                            it.name==implementation && it.isEncoder && !it.isSoftwareOnly
                        })
                    if(graph.lastFenceUsed) assertEquals("PeerConnection converted GPU texture to I420",
                        readbacks,PrivacyTextureReadbackCounter.value())
                }
                Log.i("PrivacyEncoder","local_peer codec=$codec implementation=$implementation packets=$packets " +
                    "decoded_frames_min=3 texture_readbacks=${PrivacyTextureReadbackCounter.value()-readbacks}")
                return Metrics(codec,encodeMs,PrivacyTextureReadbackCounter.value()-readbacks,encodedFrames/seconds)
            }
        } finally {
            source.capturerObserver.onCapturerStopped()
            send.connection.dispose();receive.connection.dispose()
            track.dispose();source.dispose();fixture.release();factory.dispose();egl.release()
        }
    }

    private class Peer(factory:PeerConnectionFactory,received:CountDownLatch?) {
        val gathered=CountDownLatch(1)
        val connected=CountDownLatch(1)
        val connection=checkNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()),
            object:PeerConnection.Observer {
                override fun onSignalingChange(state:PeerConnection.SignalingState)=Unit
                override fun onIceConnectionChange(state:PeerConnection.IceConnectionState)=Unit
                override fun onConnectionChange(state:PeerConnection.PeerConnectionState) {
                    if(state==PeerConnection.PeerConnectionState.CONNECTED)connected.countDown()
                }
                override fun onIceConnectionReceivingChange(receiving:Boolean)=Unit
                override fun onIceGatheringChange(state:PeerConnection.IceGatheringState) {
                    if(state==PeerConnection.IceGatheringState.COMPLETE)gathered.countDown()
                }
                override fun onIceCandidate(candidate:IceCandidate)=Unit
                override fun onIceCandidatesRemoved(candidates:Array<out IceCandidate>)=Unit
                override fun onAddStream(stream:MediaStream)=Unit
                override fun onRemoveStream(stream:MediaStream)=Unit
                override fun onDataChannel(channel:DataChannel)=Unit
                override fun onRenegotiationNeeded()=Unit
                override fun onTrack(transceiver:RtpTransceiver) {
                    (transceiver.receiver.track() as? VideoTrack)?.addSink { frame ->
                        if(frame.buffer.width>0 && frame.buffer.height>0)received?.countDown()
                    }
                }
            }))
        fun create(offer:Boolean):SessionDescription {
            var result:SessionDescription?=null
            awaitSdp { observer ->
                val creation=object:SdpObserver by observer {
                    override fun onCreateSuccess(description:SessionDescription) {result=description;observer.onSetSuccess()}
                }
                if(offer)connection.createOffer(creation,MediaConstraints())
                else connection.createAnswer(creation,MediaConstraints())
            }
            return checkNotNull(result)
        }
        fun local(sdp:SessionDescription)=awaitSdp {connection.setLocalDescription(it,sdp)}
        fun remote(sdp:SessionDescription)=awaitSdp {connection.setRemoteDescription(it,sdp)}
        private fun awaitSdp(operation:(SdpObserver)->Unit) {
            val ready=CountDownLatch(1);var error:String?=null
            operation(object:SdpObserver {
                override fun onCreateSuccess(description:SessionDescription)=Unit
                override fun onCreateFailure(message:String) {error=message;ready.countDown()}
                override fun onSetSuccess() {ready.countDown()}
                override fun onSetFailure(message:String) {error=message;ready.countDown()}
            })
            assertTrue("SDP callback timed out",ready.await(5,TimeUnit.SECONDS))
            assertNull(error)
        }
    }
}
