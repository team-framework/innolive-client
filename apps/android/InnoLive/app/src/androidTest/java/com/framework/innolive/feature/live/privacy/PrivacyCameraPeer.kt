package com.framework.innolive.feature.live.privacy

import org.junit.Assert.*
import org.webrtc.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host ICE test peer. Does not contact the server or publish a broadcast. */
    internal class PrivacyCameraPeer(factory:PeerConnectionFactory,private val onFrame:(VideoFrame)->Unit) {
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
                        onFrame(frame)
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
