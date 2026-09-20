"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/button";
import { useLocale } from "@/components/locale-provider";
import { useIsLogined } from "@/hooks/use-is-logined";
import { getInnoLiveServerUrl } from "@/lib/auth-config";
import type { Messages } from "@/lib/messages";

type ExperienceState = "connecting" | "connected" | "failed" | "ended";
type ExperienceRole = "member" | "guest";
type OutboundSignal = Record<string, unknown>;

type ServerSession = {
  sessionID: string;
  ownerToken: string;
  iceServers: RTCIceServer[];
  accessToken?: string;
  ticketID?: string;
  role: ExperienceRole;
};

type SignalingMessage = {
  type?: string;
  session_id?: string;
  negotiation_id?: string;
  sdp?: string;
  candidate?: string | null;
  sdpMid?: string | null;
  sdpMLineIndex?: number | null;
};

const experienceMetadata = {
  title: "InnoLive Web Experience",
  broadcaster_id: "landing-experience",
  client: "innolive-landing",
};

const guestPollInterval = 5_000;
const guestHeartbeatInterval = 30_000;
const connectionTimeout = 30_000;

function serverURL(path: string) {
  return `${getInnoLiveServerUrl()}${path}`;
}

function signalingURL() {
  const url = new URL(serverURL("/signaling"));
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}

function objectValue(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : null;
}

async function responseBody(response: Response) {
  try {
    return (await response.json()) as unknown;
  } catch {
    return null;
  }
}

function parseIceServers(value: unknown): RTCIceServer[] {
  if (!Array.isArray(value)) return [];

  return value.flatMap((entry) => {
    if (!entry || typeof entry !== "object") return [];
    const item = entry as {
      urls?: unknown;
      username?: unknown;
      credential?: unknown;
    };
    const urls = Array.isArray(item.urls)
      ? item.urls.filter((url): url is string => typeof url === "string")
      : typeof item.urls === "string"
        ? item.urls
        : null;
    if (!urls || (Array.isArray(urls) && urls.length === 0)) return [];
    return [
      {
        urls,
        ...(typeof item.username === "string" ? { username: item.username } : {}),
        ...(typeof item.credential === "string" ? { credential: item.credential } : {}),
      },
    ];
  });
}

function sessionFromPayload(
  payload: unknown,
  iceServers: RTCIceServer[],
  role: ExperienceRole,
  accessToken?: string,
  ticketID?: string,
) {
  const body = objectValue(payload);
  const sessionID = body?.session_id;
  const ownerToken = body?.owner_token;
  if (typeof sessionID !== "string" || typeof ownerToken !== "string") {
    throw new Error("invalid session response");
  }
  return { sessionID, ownerToken, iceServers, accessToken, ticketID, role } satisfies ServerSession;
}

async function createMemberSession(signal: AbortSignal): Promise<ServerSession> {
  const tokenResponse = await fetch("/api/auth/access-token", {
    method: "POST",
    cache: "no-store",
    credentials: "include",
    signal,
  });
  const tokenPayload = objectValue(await responseBody(tokenResponse));
  const accessToken = tokenPayload?.access_token;
  if (!tokenResponse.ok || typeof accessToken !== "string" || accessToken.length === 0) {
    throw new Error("member authentication failed");
  }

  const authorization = { Authorization: `Bearer ${accessToken}` };
  const configResponse = await fetch(serverURL("/webrtc/config"), {
    headers: authorization,
    cache: "no-store",
    signal,
  });
  const configPayload = objectValue(await responseBody(configResponse));
  if (!configResponse.ok || !configPayload) throw new Error("webrtc config failed");

  const sessionResponse = await fetch(serverURL("/sessions"), {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authorization },
    body: JSON.stringify({ metadata: experienceMetadata }),
    cache: "no-store",
    signal,
  });
  const sessionPayload = await responseBody(sessionResponse);
  if (!sessionResponse.ok) throw new Error("member session failed");

  return sessionFromPayload(
    sessionPayload,
    parseIceServers(configPayload.iceServers),
    "member",
    accessToken,
  );
}

function waitForGuestAdmission(ticketID: string, signal: AbortSignal) {
  return new Promise<string>((resolve, reject) => {
    let finished = false;
    let pollTimer: number | null = null;
    let heartbeatTimer: number | null = null;
    const finish = (callback: () => void) => {
      if (finished) return;
      finished = true;
      if (heartbeatTimer !== null) window.clearInterval(heartbeatTimer);
      if (pollTimer !== null) window.clearTimeout(pollTimer);
      callback();
    };
    heartbeatTimer = window.setInterval(() => {
      void fetch(serverURL(`/guest-queue/${encodeURIComponent(ticketID)}/heartbeat`), {
        method: "POST",
        credentials: "include",
        cache: "no-store",
        signal,
      }).then((response) => {
        if (!response.ok && !finished) {
          finish(() => reject(new Error("guest queue heartbeat failed")));
        }
      }).catch((error: unknown) => {
        if (!finished && (error as { name?: unknown }).name !== "AbortError") {
          finish(() => reject(new Error("guest queue heartbeat failed")));
        }
      });
    }, guestHeartbeatInterval);

    const poll = async () => {
      try {
        const response = await fetch(serverURL(`/guest-queue/${encodeURIComponent(ticketID)}`), {
          credentials: "include",
          cache: "no-store",
          signal,
        });
        const payload = objectValue(await responseBody(response));
        const status = payload?.status;
        const admissionToken = payload?.admission_token;
        if (!response.ok || typeof status !== "string") {
          finish(() => reject(new Error("guest queue status failed")));
          return;
        }
        if (status === "admitted" && typeof admissionToken === "string" && admissionToken.length > 0) {
          finish(() => resolve(admissionToken));
          return;
        }
        if (status !== "waiting") {
          finish(() => reject(new Error("guest queue ended")));
          return;
        }
        pollTimer = window.setTimeout(() => void poll(), guestPollInterval);
      } catch (error) {
        if ((error as { name?: unknown }).name === "AbortError") return;
        finish(() => reject(new Error("guest queue status failed")));
      }
    };

    signal.addEventListener(
      "abort",
      () => finish(() => reject(new DOMException("Aborted", "AbortError"))),
      { once: true },
    );
    void poll();
  });
}

async function createGuestSession(signal: AbortSignal, onTicket: (ticketID: string) => void) {
  const queueResponse = await fetch(serverURL("/guest-queue"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({}),
    credentials: "include",
    cache: "no-store",
    signal,
  });
  const queuePayload = objectValue(await responseBody(queueResponse));
  const ticketID = queuePayload?.ticket_id;
  if (!queueResponse.ok || !queuePayload || typeof ticketID !== "string") {
    throw new Error("guest queue unavailable");
  }
  onTicket(ticketID);

  const status = queuePayload.status;
  const initialAdmissionToken = queuePayload.admission_token;
  const admissionToken = typeof initialAdmissionToken === "string" && initialAdmissionToken.length > 0
    ? initialAdmissionToken
    : await waitForGuestAdmission(ticketID, signal);
  const sessionResponse = await fetch(serverURL("/guest-sessions"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ admission_token: admissionToken, metadata: experienceMetadata }),
    credentials: "include",
    cache: "no-store",
    signal,
  });
  const sessionPayload = await responseBody(sessionResponse);
  const sessionBody = objectValue(sessionPayload);
  if (!sessionResponse.ok || !sessionBody || typeof status !== "string") {
    throw new Error("guest session failed");
  }

  return sessionFromPayload(sessionPayload, parseIceServers(sessionBody.iceServers), "guest", undefined, ticketID);
}

function attachVideo(video: HTMLVideoElement | null, stream: MediaStream | null) {
  if (!video || !stream) return;
  if (video.srcObject !== stream) video.srcObject = stream;
  void video.play().catch(() => undefined);
}

function stopStream(stream: MediaStream | null) {
  stream?.getTracks().forEach((track) => track.stop());
}

async function deleteMemberSession(
  sessionID: string,
  ownerToken: string,
  accessToken: string,
) {
  try {
    await fetch(serverURL(`/sessions/${encodeURIComponent(sessionID)}`), {
      method: "DELETE",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "X-Session-Owner-Token": ownerToken,
      },
      cache: "no-store",
    });
  } catch {
    // Cleanup is best effort after local resources are released.
  }
}

async function deleteGuestResources(ticketID: string | null, sessionID: string | null, ownerToken: string | null) {
  const requests: Promise<unknown>[] = [];
  if (ticketID) {
    requests.push(
      fetch(serverURL(`/guest-queue/${encodeURIComponent(ticketID)}`), {
        method: "DELETE",
        credentials: "include",
        cache: "no-store",
      }).catch(() => undefined),
    );
  }
  if (sessionID && ownerToken) {
    requests.push(
      fetch(serverURL(`/guest/sessions/${encodeURIComponent(sessionID)}`), {
        method: "DELETE",
        headers: { "X-Session-Owner-Token": ownerToken },
        credentials: "include",
        cache: "no-store",
      }).catch(() => undefined),
    );
  }
  await Promise.all(requests);
}

function userMessage(error: unknown, copy: Messages["experience"]["errors"]) {
  if (error instanceof DOMException && error.name === "NotAllowedError") {
    return copy.permission;
  }
  if (error instanceof DOMException && error.name === "NotFoundError") {
    return copy.notFound;
  }
  return copy.failed;
}

export function TryOutExperience() {
  const router = useRouter();
  const { href, messages } = useLocale();
  const copy = messages.experience;
  const { isLogined, isLoading } = useIsLogined();
  const [state, setState] = useState<ExperienceState>("connecting");
  const [status, setStatus] = useState(copy.preparing);
  const localVideoRef = useRef<HTMLVideoElement | null>(null);
  const remoteVideoRef = useRef<HTMLVideoElement | null>(null);
  const peerConnectionRef = useRef<RTCPeerConnection | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const localStreamRef = useRef<MediaStream | null>(null);
  const remoteStreamRef = useRef<MediaStream | null>(null);
  const sessionRef = useRef<ServerSession | null>(null);
  const ticketIDRef = useRef<string | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const timeoutRef = useRef<number | null>(null);
  const generationRef = useRef(0);
  const pendingRemoteCandidatesRef = useRef<RTCIceCandidateInit[]>([]);

  const cleanupResources = useCallback((deleteSession: boolean) => {
    const session = sessionRef.current;
    const ticketID = ticketIDRef.current;
    const socket = socketRef.current;
    const peerConnection = peerConnectionRef.current;
    const localStream = localStreamRef.current;
    const remoteStream = remoteStreamRef.current;

    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    socketRef.current = null;
    peerConnectionRef.current = null;
    localStreamRef.current = null;
    remoteStreamRef.current = null;
    sessionRef.current = null;
    ticketIDRef.current = null;
    pendingRemoteCandidatesRef.current = [];
    if (timeoutRef.current !== null) {
      window.clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }

    socket?.close();
    peerConnection?.close();
    stopStream(localStream);
    stopStream(remoteStream);
    if (localVideoRef.current) localVideoRef.current.srcObject = null;
    if (remoteVideoRef.current) remoteVideoRef.current.srcObject = null;

    if (!deleteSession) return;
    if (session?.role === "member" && session.accessToken) {
      void deleteMemberSession(
        session.sessionID,
        session.ownerToken,
        session.accessToken,
      );
    } else if (session) {
      void deleteGuestResources(ticketID ?? session.ticketID ?? null, session.sessionID, session.ownerToken);
    } else if (ticketID) {
      void deleteGuestResources(ticketID, null, null);
    }
  }, []);

  const fail = useCallback((generation: number, error: unknown) => {
    if (generationRef.current !== generation) return;
    generationRef.current += 1;
    cleanupResources(true);
    setState("failed");
    setStatus(userMessage(error, copy.errors));
  }, [cleanupResources, copy.errors]);

  const start = useCallback(async () => {
    const generation = generationRef.current + 1;
    generationRef.current = generation;
    cleanupResources(true);
    setState("connecting");
    setStatus(copy.preparing);
    const controller = new AbortController();
    abortControllerRef.current = controller;
    const role: ExperienceRole = isLogined ? "member" : "guest";

    try {
      const session = role === "member"
        ? await createMemberSession(controller.signal)
        : await createGuestSession(controller.signal, (ticketID) => {
            if (generationRef.current === generation) ticketIDRef.current = ticketID;
          });
      if (generationRef.current !== generation) {
        if (session.role === "member" && session.accessToken) {
          await deleteMemberSession(
            session.sessionID,
            session.ownerToken,
            session.accessToken,
          );
        } else {
          await deleteGuestResources(session.ticketID ?? ticketIDRef.current, session.sessionID, session.ownerToken);
        }
        return;
      }

      sessionRef.current = session;
      if (session.ticketID) ticketIDRef.current = session.ticketID;
      setStatus(copy.checkingMedia);
      const localStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
      if (generationRef.current !== generation) {
        stopStream(localStream);
        return;
      }

      localStreamRef.current = localStream;
      attachVideo(localVideoRef.current, localStream);
      const peerConnection = new RTCPeerConnection({ iceServers: session.iceServers });
      peerConnectionRef.current = peerConnection;
      localStream.getTracks().forEach((track) => peerConnection.addTrack(track, localStream));
      const socket = new WebSocket(signalingURL());
      socketRef.current = socket;
      const negotiationID = crypto.randomUUID();
      const pendingCandidates: OutboundSignal[] = [];
      const isCurrent = () => generationRef.current === generation;
      const send = (message: OutboundSignal) => {
        if (!isCurrent() || socket.readyState !== WebSocket.OPEN) return false;
        socket.send(JSON.stringify(message));
        return true;
      };
      const authFields = session.role === "member" && session.accessToken
        ? { access_token: session.accessToken }
        : {};

      peerConnection.onicecandidate = (event) => {
        if (!event.candidate || !isCurrent()) return;
        const message: OutboundSignal = {
          type: "ice_candidate",
          session_id: session.sessionID,
          owner_token: session.ownerToken,
          ...authFields,
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex,
          negotiation_id: negotiationID,
        };
        if (!send(message)) pendingCandidates.push(message);
      };

      peerConnection.ontrack = (event) => {
        if (!isCurrent()) return;
        const stream = remoteStreamRef.current ?? event.streams[0] ?? new MediaStream();
        if (!stream.getTracks().some((track) => track.id === event.track.id)) {
          stream.addTrack(event.track);
        }
        remoteStreamRef.current = stream;
        attachVideo(remoteVideoRef.current, stream);
      };

      peerConnection.onconnectionstatechange = () => {
        if (!isCurrent()) return;
        if (peerConnection.connectionState === "connected") {
          if (timeoutRef.current !== null) {
            window.clearTimeout(timeoutRef.current);
            timeoutRef.current = null;
          }
          setState("connected");
          setStatus(copy.connected);
        } else if (["failed", "disconnected"].includes(peerConnection.connectionState)) {
          fail(generation, new Error("peer connection failed"));
        }
      };

      socket.onopen = async () => {
        try {
          if (!isCurrent()) return;
          for (const candidate of pendingCandidates.splice(0)) send(candidate);
          setStatus(copy.startingWebrtc);
          const offer = await peerConnection.createOffer();
          await peerConnection.setLocalDescription(offer);
          if (!isCurrent()) return;
          send({
            type: "offer",
            session_id: session.sessionID,
            owner_token: session.ownerToken,
            ...authFields,
            sdp: offer.sdp,
            negotiation_id: negotiationID,
          });
        } catch (error) {
          fail(generation, error);
        }
      };

      socket.onmessage = async (event) => {
        try {
          if (!isCurrent()) return;
          const message = JSON.parse(String(event.data)) as SignalingMessage;
          if (message.session_id && message.session_id !== session.sessionID) return;
          if (message.negotiation_id && message.negotiation_id !== negotiationID) return;
          if (message.type === "error") throw new Error("signaling rejected");
          if (message.type === "answer" && typeof message.sdp === "string") {
            await peerConnection.setRemoteDescription({ type: "answer", sdp: message.sdp });
            for (const candidate of pendingRemoteCandidatesRef.current.splice(0)) {
              await peerConnection.addIceCandidate(candidate);
            }
            return;
          }
          if (message.type === "ice_candidate" && typeof message.candidate === "string") {
            const candidate = {
              candidate: message.candidate,
              sdpMid: message.sdpMid ?? null,
              sdpMLineIndex: message.sdpMLineIndex ?? null,
            } satisfies RTCIceCandidateInit;
            if (peerConnection.remoteDescription) await peerConnection.addIceCandidate(candidate);
            else pendingRemoteCandidatesRef.current.push(candidate);
          }
        } catch (error) {
          fail(generation, error);
        }
      };

      socket.onerror = () => {
        if (isCurrent()) fail(generation, new Error("signaling failed"));
      };
      socket.onclose = () => {
        if (isCurrent() && peerConnection.connectionState !== "connected") {
          fail(generation, new Error("signaling closed"));
        }
      };
      timeoutRef.current = window.setTimeout(() => {
        if (isCurrent()) fail(generation, new Error("connection timed out"));
      }, connectionTimeout);
    } catch (error) {
      if (generationRef.current === generation && (error as { name?: unknown }).name !== "AbortError") {
        fail(generation, error);
      }
    }
  }, [cleanupResources, copy.checkingMedia, copy.connected, copy.preparing, copy.startingWebrtc, fail, isLogined]);

  const end = useCallback(() => {
    generationRef.current += 1;
    cleanupResources(true);
    setState("ended");
    setStatus(copy.ended);
    router.replace(href("/try-out"));
  }, [cleanupResources, copy.ended, href, router]);

  useEffect(() => {
    if (isLoading) return;
    const timer = window.setTimeout(() => void start(), 0);
    return () => window.clearTimeout(timer);
  }, [isLoading, start]);

  useEffect(() => () => {
    generationRef.current += 1;
    cleanupResources(true);
  }, [cleanupResources]);

  const isBusy = state === "connecting" || isLoading;
  return (
    <section
      className="flex w-full flex-col items-center gap-10 px-[var(--page-gutter)] pb-16 pt-16 lg:pt-24"
      aria-labelledby="try-out-experience-heading"
    >
      <div className="flex w-full max-w-[100rem] flex-col items-center gap-4 text-center">
        <h1
          id="try-out-experience-heading"
          className="break-keep text-[clamp(2rem,1.2rem+3.2vw,4rem)] font-bold leading-none"
        >
          {copy.title}
        </h1>
        <p role="status" aria-live="polite" className="text-body-lg text-text-secondary">
          {status}
        </p>
      </div>

      <div className="flex w-full max-w-[100rem] flex-col gap-3 lg:flex-row">
        <div className="relative aspect-video w-full overflow-hidden rounded-[12px] bg-background-secondary">
          <video ref={remoteVideoRef} autoPlay muted playsInline className="size-full object-contain" aria-label={copy.remoteLabel} />
          {state !== "connected" ? (
            <p className="absolute inset-0 flex items-center justify-center px-4 text-center text-body text-text-secondary">
              {copy.remotePlaceholder}
            </p>
          ) : null}
        </div>
        <div className="relative aspect-video w-full overflow-hidden rounded-[12px] bg-background-secondary">
          <video ref={localVideoRef} autoPlay playsInline muted className="size-full object-contain" aria-label={copy.localLabel} />
          <p className="absolute bottom-3 left-3 rounded-pill bg-background-primary/80 px-3 py-1 text-sm text-text-primary">
            {copy.localBadge}
          </p>
        </div>
      </div>

      <div className="flex flex-wrap justify-center gap-3">
        {state === "failed" ? (
          <Button showChevron={false} onClick={() => void start()}>
            {copy.retry}
          </Button>
        ) : null}
        {state !== "ended" ? (
          <Button variant="secondary" showChevron={false} onClick={end} disabled={isBusy}>
            {copy.end}
          </Button>
        ) : (
          <Button showChevron={false} onClick={() => void start()}>
            {copy.restart}
          </Button>
        )}
      </div>
    </section>
  );
}
