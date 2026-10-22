/* Pulse voice: real WebRTC audio, with signaling between tabs on this origin.
 * BroadcastChannel cannot connect other browsers or devices. Internet voice
 * requires a shared signaling service and, for reliable connectivity, TURN.
 */
(() => {
  'use strict';

  const peers = new Map();
  const HEARTBEAT_MS = 4000;
  const STALE_MS = 16000;
  let identity = { userId: '', displayName: 'You' };
  let onChange = null;
  let toast = null;
  let generation = 0;
  let stream = null;
  let channel = null;
  let heartbeat = null;
  let peerId = '';
  let beforeDeafen = false;
  let disposed = false;
  let state = emptyState();

  function emptyState() {
    return { status: 'disconnected', roomId: null, name: '', serverName: '', muted: false, deafened: false, error: '', localOnly: true };
  }

  function snapshot() {
    return {
      ...state,
      autoplayBlocked: [...peers.values()].some((peer) => peer.autoplayBlocked),
      participants: stream ? [
        { peerId, ...identity, isSelf: true, muted: state.muted, connectionState: 'connected' },
        ...[...peers.values()].map((peer) => ({
          peerId: peer.id, userId: peer.userId, displayName: peer.displayName,
          isSelf: false, muted: peer.muted, connectionState: peer.pc.connectionState || 'new'
        }))
      ] : []
    };
  }

  function emit() {
    const detail = snapshot();
    document.dispatchEvent(new CustomEvent('pulse:voice-state', { detail }));
    if (onChange) {
      try { onChange(detail); } catch (error) { console.error('Pulse voice view could not update.', error); }
    }
    return detail;
  }

  function notify(message) { if (typeof toast === 'function') toast(message); }
  function validString(value, length = 200) { return typeof value === 'string' ? value.slice(0, length) : ''; }
  function metadata() { return { userId: identity.userId, displayName: identity.displayName, muted: state.muted }; }

  function send(type, extra = {}) {
    if (!channel) return;
    try {
      channel.postMessage({ type, from: peerId, roomId: state.roomId, ...metadata(), ...extra });
    } catch { /* A leave or page close may race a queued ICE event. */ }
  }

  function removePeer(id) {
    const peer = peers.get(id);
    if (!peer) return;
    peers.delete(id);
    peer.pc.onicecandidate = null;
    peer.pc.ontrack = null;
    peer.pc.onconnectionstatechange = null;
    peer.pc.close();
    if (peer.audio) {
      peer.audio.pause();
      peer.audio.srcObject = null;
      peer.audio.remove();
    }
  }

  function cleanup() {
    send('leave');
    clearInterval(heartbeat);
    heartbeat = null;
    if (channel) {
      channel.onmessage = null;
      channel.close();
      channel = null;
    }
    for (const id of [...peers.keys()]) removePeer(id);
    const previousStream = stream;
    stream = null;
    if (previousStream) previousStream.getTracks().forEach((track) => track.stop());
    peerId = '';
    beforeDeafen = false;
  }

  function leave() {
    generation += 1;
    cleanup();
    state = emptyState();
    return emit();
  }

  async function playAudio(peer) {
    if (!peer.audio || peers.get(peer.id) !== peer) return;
    try {
      await peer.audio.play();
      if (peers.get(peer.id) !== peer) return;
      if (peer.autoplayBlocked) { peer.autoplayBlocked = false; emit(); }
    } catch (error) {
      if (peers.get(peer.id) !== peer || !stream) return;
      // A stream may be attached before its first playable audio frame.
      if (error.name !== 'NotAllowedError') return;
      const alreadyBlocked = snapshot().autoplayBlocked;
      peer.autoplayBlocked = true;
      emit();
      if (!alreadyBlocked) notify('Your browser paused voice audio. Select Enable audio in the voice controls.');
    }
  }

  async function resumeAudio() {
    await Promise.all([...peers.values()].map((peer) => playAudio(peer)));
    return snapshot();
  }

  function makePeer(message) {
    if (!stream) return null;
    const existing = peers.get(message.from);
    if (existing) {
      existing.lastSeen = Date.now();
      existing.userId = validString(message.userId);
      existing.displayName = validString(message.displayName, 80) || 'Pulse user';
      existing.muted = Boolean(message.muted);
      return existing;
    }
    const pc = new RTCPeerConnection({ iceServers: [] });
    const peer = {
      id: message.from, userId: validString(message.userId),
      displayName: validString(message.displayName, 80) || 'Pulse user',
      muted: Boolean(message.muted), lastSeen: Date.now(), pc,
      candidates: [], queue: Promise.resolve(), audio: null, autoplayBlocked: false,
      offered: false, token: generation
    };
    peers.set(peer.id, peer);
    for (const track of stream.getAudioTracks()) pc.addTrack(track, stream);
    pc.onicecandidate = (event) => {
      if (event.candidate && peers.get(peer.id) === peer) {
        send('candidate', { to: peer.id, candidate: event.candidate.toJSON ? event.candidate.toJSON() : event.candidate });
      }
    };
    pc.onconnectionstatechange = () => {
      if (peers.get(peer.id) !== peer) return;
      if (pc.connectionState === 'failed') {
        // Both endpoints rebuild their connection when the next presence arrives.
        send('reset', { to: peer.id });
        removePeer(peer.id);
      }
      emit();
    };
    pc.ontrack = (event) => {
      if (peers.get(peer.id) !== peer || event.track.kind !== 'audio') return;
      if (!peer.audio) {
        peer.audio = document.createElement('audio');
        peer.audio.autoplay = true;
        peer.audio.playsInline = true;
        peer.audio.setAttribute('aria-label', `Voice audio from ${peer.displayName}`);
        peer.audio.dataset.voicePeer = peer.id;
        let host = document.getElementById('remoteAudio');
        if (!host) {
          host = document.createElement('div');
          host.id = 'remoteAudio';
          host.hidden = true;
          document.body.append(host);
        }
        host.append(peer.audio);
      }
      peer.audio.muted = state.deafened;
      peer.audio.srcObject = event.streams[0] || new MediaStream([event.track]);
      void playAudio(peer);
    };
    return peer;
  }

  function currentPeer(peer) { return peer.token === generation && peers.get(peer.id) === peer; }

  function enqueue(peer, work) {
    peer.queue = peer.queue.then(async () => {
      if (currentPeer(peer)) await work();
    }).catch((error) => {
      if (!currentPeer(peer)) return;
      console.warn('Pulse voice could not connect a participant.', error.name || error);
      send('reset', { to: peer.id });
      removePeer(peer.id);
      emit();
    });
  }

  function offerIfNeeded(peer) {
    // Exactly one endpoint creates the offer, even with simultaneous joins.
    if (peer.offered || peerId > peer.id) return;
    peer.offered = true;
    enqueue(peer, async () => {
      const offer = await peer.pc.createOffer();
      if (!currentPeer(peer)) return;
      await peer.pc.setLocalDescription(offer);
      if (currentPeer(peer)) send('offer', { to: peer.id, description: { type: peer.pc.localDescription.type, sdp: peer.pc.localDescription.sdp } });
    });
  }

  async function flushCandidates(peer) {
    const candidates = peer.candidates.splice(0);
    for (const candidate of candidates) {
      if (!currentPeer(peer)) return;
      await peer.pc.addIceCandidate(candidate);
    }
  }

  function receive(event) {
    const message = event.data;
    if (!message || typeof message !== 'object' || !stream ||
      typeof message.from !== 'string' || message.from.length > 100 || !message.from ||
      message.from === peerId || message.roomId !== state.roomId ||
      (message.to && message.to !== peerId)) return;
    if (message.type === 'leave' || message.type === 'reset') {
      removePeer(message.from);
      emit();
      return;
    }
    if (!['hello', 'presence', 'offer', 'answer', 'candidate'].includes(message.type)) return;
    let peer;
    try { peer = makePeer(message); }
    catch { notify('Your browser could not create an audio connection. Try leaving and rejoining voice.'); return; }
    if (!peer) return;
    if (message.type === 'hello' || message.type === 'presence') {
      if (message.type === 'hello') send('presence', { to: peer.id });
      offerIfNeeded(peer);
    } else if (message.type === 'offer' && message.from < peerId && message.description?.type === 'offer') {
      enqueue(peer, async () => {
        await peer.pc.setRemoteDescription(message.description);
        if (!currentPeer(peer)) return;
        await flushCandidates(peer);
        if (!currentPeer(peer)) return;
        const answer = await peer.pc.createAnswer();
        if (!currentPeer(peer)) return;
        await peer.pc.setLocalDescription(answer);
        if (currentPeer(peer)) send('answer', { to: peer.id, description: { type: peer.pc.localDescription.type, sdp: peer.pc.localDescription.sdp } });
      });
    } else if (message.type === 'answer' && peer.offered && message.description?.type === 'answer') {
      enqueue(peer, async () => {
        if (peer.pc.signalingState !== 'have-local-offer') return;
        await peer.pc.setRemoteDescription(message.description);
        if (currentPeer(peer)) await flushCandidates(peer);
      });
    } else if (message.type === 'candidate' && message.candidate && typeof message.candidate.candidate === 'string') {
      enqueue(peer, async () => {
        if (peer.pc.remoteDescription) await peer.pc.addIceCandidate(message.candidate);
        else if (peer.candidates.length < 100) peer.candidates.push(message.candidate);
      });
    }
    emit();
  }

  function micError(error) {
    if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') return 'Microphone access was denied. Allow the microphone for this site in your browser, then rejoin.';
    if (error.name === 'NotFoundError' || error.name === 'DevicesNotFoundError') return 'No microphone was found. Connect a microphone and try again.';
    if (error.name === 'NotReadableError' || error.name === 'TrackStartError') return 'Your microphone could not be opened. Check whether another application is using it.';
    if (error.name === 'SecurityError') return 'Your browser blocked microphone access. Open Pulse over HTTPS or localhost.';
    return error.message || 'Voice could not start. Check your microphone and try again.';
  }

  async function join(options = {}) {
    const roomId = validString(options.roomId, 300);
    if (!roomId) throw new Error('Choose a voice room first.');
    if (disposed) throw new Error('Voice has been disposed. Call init before joining.');
    if (state.roomId === roomId && (state.status === 'connected' || state.status === 'connecting')) return snapshot();
    generation += 1;
    const token = generation;
    cleanup();
    identity = {
      userId: validString(options.userId || identity.userId),
      displayName: validString(options.displayName || identity.displayName, 80) || 'You'
    };
    state = { ...emptyState(), status: 'connecting', roomId, name: validString(options.name, 100) || 'Voice', serverName: validString(options.serverName, 100) };
    emit();
    try {
      if (!identity.userId) throw new Error('Sign in before joining a voice room.');
      if (!navigator.mediaDevices?.getUserMedia) throw new Error('Microphone access requires HTTPS or localhost and a supported browser.');
      if (typeof BroadcastChannel !== 'function' || typeof RTCPeerConnection !== 'function') throw new Error('This browser does not support Pulse voice. Try a current browser.');
      const captured = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: false });
      if (generation !== token) {
        captured.getTracks().forEach((track) => track.stop());
        return snapshot();
      }
      stream = captured;
      if (!stream.getAudioTracks().some((track) => track.readyState === 'live')) throw new Error('Your microphone did not provide an audio track. Check your input device.');
      for (const track of stream.getAudioTracks()) {
        track.addEventListener('ended', () => {
          if (stream !== captured || generation !== token) return;
          generation += 1;
          cleanup();
          state = { ...emptyState(), status: 'error', error: 'Your microphone was disconnected. Reconnect it and join again.' };
          emit();
          notify(state.error);
        });
      }
      peerId = globalThis.crypto?.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
      channel = new BroadcastChannel(`pulse-voice-v1:${roomId}`);
      channel.onmessage = receive;
      state.status = 'connected';
      heartbeat = setInterval(() => {
        let changed = false;
        for (const peer of peers.values()) {
          if (Date.now() - peer.lastSeen > STALE_MS) { removePeer(peer.id); changed = true; }
        }
        send('presence');
        if (changed) emit();
      }, HEARTBEAT_MS);
      send('hello');
      return emit();
    } catch (error) {
      if (generation !== token) return snapshot();
      cleanup();
      state = { ...emptyState(), status: 'error', error: micError(error) };
      emit();
      notify(state.error);
      return snapshot();
    }
  }

  function applyAudioState() {
    if (stream) stream.getAudioTracks().forEach((track) => { track.enabled = !state.muted; });
    for (const peer of peers.values()) if (peer.audio) peer.audio.muted = state.deafened;
    send('presence');
    return emit();
  }

  function toggleMute() {
    if (!stream) return snapshot();
    if (state.deafened) { state.deafened = false; state.muted = false; }
    else state.muted = !state.muted;
    return applyAudioState();
  }

  function toggleDeafen() {
    if (!stream) return snapshot();
    state.deafened = !state.deafened;
    if (state.deafened) { beforeDeafen = state.muted; state.muted = true; }
    else state.muted = beforeDeafen;
    return applyAudioState();
  }

  function init(options = {}) {
    disposed = false;
    if (options.userId) identity.userId = validString(options.userId);
    if (options.displayName) identity.displayName = validString(options.displayName, 80);
    if ('onChange' in options) onChange = typeof options.onChange === 'function' ? options.onChange : null;
    if ('toast' in options) toast = typeof options.toast === 'function' ? options.toast : null;
    send('presence');
    return emit();
  }

  function dispose() {
    leave();
    onChange = null;
    toast = null;
    disposed = true;
  }

  window.addEventListener('pagehide', leave);
  document.addEventListener('pulse:logout', leave);
  window.PulseVoice = Object.freeze({ init, join, leave, toggleMute, toggleDeafen, resumeAudio, snapshot, dispose });
})();
