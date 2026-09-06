const targetResponse = await fetch('http://127.0.0.1:9222/json/new?http://127.0.0.1:8080/?listeningDebug=1', { method: 'PUT' });
if (!targetResponse.ok) throw new Error(`Could not create Chrome target: ${targetResponse.status}`);
const target = await targetResponse.json();
const socket = new WebSocket(target.webSocketDebuggerUrl);
let nextId = 1;
const pending = new Map();
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  const resolver = pending.get(message.id);
  if (resolver) { pending.delete(message.id); resolver(message); }
});
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true });
  socket.addEventListener('error', reject, { once: true });
});
const command = (method, params = {}) => new Promise((resolve, reject) => {
  const id = nextId++;
  pending.set(id, (message) => message.error ? reject(new Error(JSON.stringify(message.error))) : resolve(message.result));
  socket.send(JSON.stringify({ id, method, params }));
});
await command('Runtime.enable');
await command('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, x: 10, y: 10 });
await command('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, x: 10, y: 10 });
const evaluation = await command('Runtime.evaluate', {
  awaitPromise: true,
  returnByValue: true,
  expression: `(async () => {
    const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
    await wait(2000);
    localStorage.removeItem('moodify_listening_events');
    localStorage.removeItem('moodify_listening_session');
    const engine = window.__moodifyPlaybackEngine;
    if (!engine) throw new Error('canonical playback engine not found');
    await engine.load({ id: 'J7p4bzqLvCw', provider: 'youtube-music', providerId: 'J7p4bzqLvCw', title: 'Blinding Lights', artist: 'The Weeknd', album: 'After Hours' }, true);
    for (let i = 0; i < 40; i++) { await wait(500); if (window.__moodifyPlaybackEngine?.getSnapshot().status === 'playing') break; }
    const started = engine.getSnapshot();
    engine.pause();
    await wait(500);
    const paused = engine.getSnapshot();
    await engine.play();
    await wait(1000);
    const resumed = engine.getSnapshot();
    const events = JSON.parse(localStorage.getItem('moodify_listening_events') || '[]');
    return { started, paused, resumed, eventTypes: events.map((event) => event.type), events };
  })()`
});
console.log(JSON.stringify({ value: evaluation.result?.value, exception: evaluation.exceptionDetails }, null, 2));
socket.close();
