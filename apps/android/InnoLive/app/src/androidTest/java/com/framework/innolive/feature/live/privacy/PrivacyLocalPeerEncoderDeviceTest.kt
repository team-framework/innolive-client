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
        val baseline=runPeer(false);val preferred=runPeer(true)
        Log.i("PrivacyEncoder","codec_comparison baseline_codec=${baseline.codec} candidate_codec=${preferred.codec} " +
            "baseline_encode_mean_ms=${baseline.encodeMeanMs} candidate_encode_mean_ms=${preferred.encodeMeanMs} " +
            "baseline_cpu_readbacks=${baseline.readbacks} candidate_cpu_readbacks=${preferred.readbacks}")
    }
    private data class Metrics(val codec:String?,val encodeMeanMs:Double,val readbacks:Long)
    private fun runPeer(preferHardware:Boolean):Metrics {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl=EglBase.create()
        val factory=PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext,true,true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val received=CountDownLatch(3)
        val source=factory.createVideoSource(false)
        val track=factory.createVideoTrack("local-protected-video",source)
        val send=Peer(factory,null)
        val receive=Peer(factory,received)
        val fixture=JavaI420Buffer.allocate(320,320)
        repeat(fixture.dataY.capacity()) { fixture.dataY.put(it,(if(it%320<160)32 else 220).toByte()) }
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
            source.capturerObserver.onCapturerStarted(true)
            PrivacyGpuFramePipeline(egl.eglBaseContext,useGles3=true,useFence=true).use { graph ->
                val readbacks=PrivacyTextureReadbackCounter.value()
                repeat(60) { index ->
                    val layout=graph.prepare(fixture,0,readModel=false)
                    val texture=graph.finish(ByteArray(160*160) {-1},layout,320,320)
                    val frame=VideoFrame(texture,0,System.nanoTime())
                    try {source.capturerObserver.onFrameCaptured(frame)} finally {frame.release()}
                    Thread.sleep(33)
                }
                assertTrue("No decoded protected frames",received.await(5,TimeUnit.SECONDS))
                val ready=CountDownLatch(1)
                var codec:String?=null;var implementation:String?=null;var packets=0L
                var encodeMs=Double.NaN
                send.connection.getStats { report ->
                    val outbound=report.statsMap.values.firstOrNull {
                        it.type=="outbound-rtp" && (it.members["kind"]=="video" || it.members["mediaType"]=="video")
                    }
                    outbound?.let {
                        implementation=it.members["encoderImplementation"] as? String
                        packets=(it.members["packetsSent"] as? Number)?.toLong() ?: 0
                        codec=report.statsMap[it.members["codecId"]]?.members?.get("mimeType") as? String
                        val frames=(it.members["framesEncoded"] as? Number)?.toDouble() ?: 0.0
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
                return Metrics(codec,encodeMs,PrivacyTextureReadbackCounter.value()-readbacks)
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
