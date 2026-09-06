const targetResponse = await fetch('http://127.0.0.1:9222/json/new?http://127.0.0.1:8787/smoke', { method: 'PUT' });
if (!targetResponse.ok) throw new Error(`Could not create Chrome target: ${targetResponse.status}`);
const target = await targetResponse.json();
console.log(JSON.stringify({ event: 'target', id: target.id, url: target.url }));

const socket = new WebSocket(target.webSocketDebuggerUrl);
let nextId = 1;
const pending = new Map();
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  const resolver = pending.get(message.id);
  if (resolver) {
    pending.delete(message.id);
    resolver(message);
  }
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
const evaluation = await command('Runtime.evaluate', {
  awaitPromise: true,
  returnByValue: true,
  expression: `(async () => {
    const audio = document.querySelector('#audio');
    const events = [];
    for (const name of ['loadstart', 'loadedmetadata', 'durationchange', 'canplay', 'play', 'playing', 'waiting', 'stalled', 'progress', 'timeupdate', 'error', 'ended']) {
      audio.addEventListener(name, () => events.push({ name, time: audio.currentTime, readyState: audio.readyState, networkState: audio.networkState, error: audio.error && { code: audio.error.code, message: audio.error.message } }));
    }
    while (document.querySelector('#play').disabled) await new Promise((resolve) => setTimeout(resolve, 250));
    document.querySelector('#play').click();
    await new Promise((resolve) => setTimeout(resolve, 35000));
    return { events, currentTime: audio.currentTime, duration: audio.duration, readyState: audio.readyState, networkState: audio.networkState, error: audio.error && { code: audio.error.code, message: audio.error.message }, performance: performance.getEntriesByType('resource').filter((entry) => entry.name.includes('/api/media/')).map((entry) => entry.name) };
  })()`
});
console.log(JSON.stringify({ event: 'evaluation', value: evaluation.result?.value, exception: evaluation.exceptionDetails }, null, 2));
socket.close();
