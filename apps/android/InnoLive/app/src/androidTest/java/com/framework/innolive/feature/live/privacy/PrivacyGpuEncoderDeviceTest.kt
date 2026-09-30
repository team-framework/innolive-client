package com.framework.innolive.feature.live.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglBase
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EncodedImage
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoFrame
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import com.framework.innolive.feature.live.preferredHardwareVideoCodecs
import com.framework.innolive.feature.live.preferredServerVideoCodecs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the same shared EGL surface encoder used for broadcasting, without signaling/network. */
@RunWith(AndroidJUnit4::class)
class PrivacyGpuEncoderDeviceTest {
    @Test fun serverVp8IsFirstInRealAndroidOffer() = codecFirstInRealAndroidOffer(true)
    @Test fun hardwareBaselineH264IsFirstInRealAndroidOffer() = codecFirstInRealAndroidOffer(false)
    private fun codecFirstInRealAndroidOffer(serverPath: Boolean) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl=EglBase.create()
        val factory=PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext,true,true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val connection=checkNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()),
            object:PeerConnection.Observer {
                override fun onSignalingChange(state:PeerConnection.SignalingState)=Unit
                override fun onIceConnectionChange(state:PeerConnection.IceConnectionState)=Unit
                override fun onIceConnectionReceivingChange(receiving:Boolean)=Unit
                override fun onIceGatheringChange(state:PeerConnection.IceGatheringState)=Unit
                override fun onIceCandidate(candidate:IceCandidate)=Unit
                override fun onIceCandidatesRemoved(candidates:Array<out IceCandidate>)=Unit
                override fun onAddStream(stream:MediaStream)=Unit
                override fun onRemoveStream(stream:MediaStream)=Unit
                override fun onDataChannel(channel:DataChannel)=Unit
                override fun onRenegotiationNeeded()=Unit
            }))
        try {
            val transceiver=checkNotNull(connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)))
            val codecs=factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val hardware=HardwareVideoEncoderFactory(egl.eglBaseContext,true,true).supportedCodecs.toList()
            val preferred = checkNotNull(if (serverPath) preferredServerVideoCodecs(codecs)
                else preferredHardwareVideoCodecs(codecs, hardware))
            assertTrue(transceiver.setCodecPreferences(preferred).isSuccess())
            val offered=CountDownLatch(1)
            var sdp:String?=null
            connection.createOffer(object:SdpObserver {
                override fun onCreateSuccess(description:SessionDescription) {sdp=description.description;offered.countDown()}
                override fun onCreateFailure(error:String) {offered.countDown()}
                override fun onSetSuccess()=Unit
                override fun onSetFailure(error:String)=Unit
            },MediaConstraints())
            assertTrue("No WebRTC offer",offered.await(5,TimeUnit.SECONDS))
            val description=checkNotNull(sdp)
            val firstPayload=checkNotNull(description.lineSequence().firstOrNull {it.startsWith("m=video ")})
                .trim().split(' ')[3]
            val expectedCodec = if (serverPath) "VP8" else "H264"
            assertTrue("$expectedCodec was not first: $firstPayload",
                description.contains("a=rtpmap:$firstPayload $expectedCodec/90000"))
            if (!serverPath) assertTrue(description.contains("profile-level-id=42e01f",ignoreCase=true))
        } finally {connection.dispose();factory.dispose();egl.release()}
    }
    @Test fun productionFactoryReportsHardwareForNegotiableCodecs() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl=EglBase.create()
        try {
            val factory=DefaultVideoEncoderFactory(egl.eglBaseContext,true,true)
            val codecs=factory.supportedCodecs.filter {it.name.equals("H264",true) || it.name.equals("VP8",true)}
            assertTrue("No WebRTC video codec is available",codecs.isNotEmpty())
            for(codec in codecs) {
                val encoder=checkNotNull(factory.createEncoder(codec))
                Log.i("PrivacyEncoder","production_codec=${codec.name} params=${codec.params} hardware=${encoder.isHardwareEncoder}")
                if(encoder.isHardwareEncoder) runCatching {encoder.release()}
            }
        } finally {egl.release()}
    }
    @Test fun protectedTextureEncodesOnHardwareSurfaceWithoutCpuI420Output() = encode(false)
    @Test fun borrowedCameraInputEncodesAfterCameraStorageIsReleased() = encode(true)
    @Test fun protected1080pTextureEncodesOnHardwareWithoutCpuReadback() = encode(false,1920,1080)
    private fun encode(direct:Boolean,width:Int=320,height:Int=320) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl = EglBase.create()
        val factory = HardwareVideoEncoderFactory(egl.eglBaseContext, true, true)
        val codec = checkNotNull(factory.supportedCodecs.firstOrNull { it.name == "H264" })
        val encoder = checkNotNull(factory.createEncoder(codec))
        assertTrue(encoder.isHardwareEncoder)
        val encoded = CountDownLatch(3)
        val errors = AtomicInteger()
        val source = JavaI420Buffer.allocate(width,height)
        val rotation=if(width==height)90 else 0
        repeat(source.dataY.capacity()) { source.dataY.put(it, (if (it % 2 == 0) 16 else 235).toByte()) }
        repeat(source.dataU.capacity()) { source.dataU.put(it, 128.toByte()) }
        repeat(source.dataV.capacity()) { source.dataV.put(it, 128.toByte()) }
        try {
            assertEquals(VideoCodecStatus.OK, encoder.initEncode(
                VideoEncoder.Settings(4,width,height,if(width>320)6000 else 500,30,1,false,VideoEncoder.Capabilities(false))) { image, _ ->
                if (image.encodedWidth != width || image.encodedHeight != height || image.buffer.remaining() == 0)
                    errors.incrementAndGet()
                encoded.countDown()
            })
            PrivacyGpuFramePipeline(egl.eglBaseContext, useGles3=true, useFence=true).use { graph ->
                val readbacks=PrivacyTextureReadbackCounter.value()
                val mask = ByteArray(160 * 160) { -1 }
                repeat(12) { index ->
                    val fixture=if(direct) PrivacyCameraFixture.create(width,height,rotation,"NV21") else null
                    val layout=if(fixture!=null) {
                        graph.prepare(fixture.camera,readModel=false).also {
                            assertEquals(0,graph.lastCameraCopiedPlanes)
                            fixture.camera.close();fixture.poison()
                        }
                    } else graph.prepare(source,rotation)
                    val texture = graph.finish(mask,layout,width,height)
                    val frame = VideoFrame(texture,rotation,System.nanoTime())
                    try {
                        assertEquals(VideoCodecStatus.OK, encoder.encode(frame, VideoEncoder.EncodeInfo(arrayOf(
                            if (index == 0) EncodedImage.FrameType.VideoFrameKey else EncodedImage.FrameType.VideoFrameDelta))))
                    } finally { frame.release() }
                    Thread.sleep(40)
                }
                assertTrue("No hardware encoded frames", encoded.await(5, TimeUnit.SECONDS))
                if(graph.lastFenceUsed) assertEquals("Hardware encoder requested CPU I420",
                    readbacks,PrivacyTextureReadbackCounter.value())
                assertEquals(0, errors.get())
                assertEquals(VideoCodecStatus.OK, encoder.release())
            }
        } finally { runCatching { encoder.release() }; source.release(); egl.release() }
    }
}
