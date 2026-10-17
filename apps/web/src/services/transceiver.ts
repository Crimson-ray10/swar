// One UI contract for the existing Android bridge and Node backend.
export type RadioEvent = (type: string, data: any) => void;
declare global {
  interface Window {
    SWAR?: { command(action: string, data: string): void };
    onNative?: RadioEvent;
  }
}
export const isNative = () => !!window.SWAR;
export function openRadio(emit: RadioEvent) {
  let closed = false;
  let socket: WebSocket | undefined;
  let retry: ReturnType<typeof setTimeout> | undefined;
  let selfId = '';
  let language = 'en';
  let peers: any[] = [];
  const report = (error: unknown) => emit('error', { message: String(error) });
  const request = async (path: string, body?: unknown) => {
    const response = await fetch('/api/v1/voicebridge/' + path, body === undefined ? {} : {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    const data = await response.json();
    if (!response.ok || data.success === false) throw new Error(data.error || 'Backend request failed');
    return data;
  };
  const roster = (clients: any[]) => {
    emit('peers', { peers: [...peers, ...clients.filter(p => p.id !== selfId).map(p => ({ ...p, address: p.ip, transport: 'web_relay' }))] });
    emit('connection', { state: clients.some(p => p.id !== selfId) ? 'CONNECTED' : 'DISCONNECTED', peer: 'Web relay channel' });
  };
  const connectSocket = () => {
    socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/api/v1/voicebridge/ws`);
    socket.onmessage = ({ data }) => {
      const event = JSON.parse(data);
      const d = event.data;
      switch (event.event) {
        case 'init': selfId = d.selfClient.id; peers = d.peers; roster(d.webClients); break;
        case 'web_clients_updated': roster(d); break;
        case 'peer_found': case 'peer_updated': emit('peer', d); break;
        case 'peer_lost': emit('peerLost', d); break;
        case 'supervisor_state': emit('connection', d); break;
        case 'transcript_partial': emit('partial', d); break;
        case 'message_sent': emit('sent', d); break;
        case 'voice_message_received':
          emit('received', d);
          if ('speechSynthesis' in window) {
            const speech = new SpeechSynthesisUtterance(d.text);
            speech.lang = d.language || 'en';
            speech.onend = () => emit('speech', { state: 'Idle' });
            speech.onerror = () => emit('error', { message: 'Speech playback unavailable. Read the received transcript.' });
            window.speechSynthesis.speak(speech);
          }
          break;
      }
    };
    socket.onclose = () => {
      if (closed) return;
      emit('connection', { state: 'DISCONNECTED' });
      emit('notice', { message: 'Backend disconnected; reconnecting…' });
      retry = setTimeout(connectSocket, 3000);
    };
    socket.onerror = () => emit('notice', { message: 'Start the Swar backend to connect.' });
  };
  if (isNative()) window.onNative = emit;
  else connectSocket();
  const command = (action: string, data: any = {}) => {
    if (closed) return;
    if (window.SWAR) { window.SWAR.command(action, JSON.stringify(data)); return; }
    switch (action) {
      case 'language': language = data.language; break;
      case 'send': void request('send', { text: data.text, language }).catch(report); break;
      case 'discover': void request('peers').then(p => p.forEach((peer: any) => emit('peer', peer))).catch(report); break;
      case 'connect':
        if (data.peer.transport === 'web_relay') emit('connection', { state: 'CONNECTED', peer: 'Web relay channel (all clients)' });
        else void request('connect', { peer: data.peer }).catch(report);
        break;
      case 'disconnect': void request('disconnect', {}).catch(report); break;
      case 'capabilities': break;
      case 'stopPlayback': window.speechSynthesis?.cancel(); emit('speech', { state: 'Idle' }); break;
      default: emit('notice', { message: 'This control requires the Android app.' });
    }
  };
  return { command, close() {
    closed = true; clearTimeout(retry); socket?.close(); window.speechSynthesis?.cancel();
    if (isNative()) { window.SWAR?.command('cancel', '{}'); if (window.onNative === emit) delete window.onNative; }
  } };
}
