const targetResponse = await fetch('http://127.0.0.1:9222/json/new?http://127.0.0.1:8787/smoke', { method: 'PUT' });
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
const result = await command('Runtime.evaluate', {
  awaitPromise: true,
  returnByValue: true,
  expression: `(async () => {
    const audio = document.querySelector('#audio');
    const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
    const waitFor = (event, timeout = 15000) => new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout waiting for ' + event)), timeout);
      audio.addEventListener(event, () => { clearTimeout(timer); resolve(); }, { once: true });
    });
    const queries = [
      { name: 'The Weeknd - Blinding Lights', query: 'The Weeknd Blinding Lights' },
      { name: 'Arijit Singh - Tum Hi Ho', query: 'Arijit Singh Tum Hi Ho' },
      { name: 'Anuv Jain - Alag Aasmaan', query: 'Anuv Jain Alag Aasmaan' }
    ];
    const results = [];
    for (const item of queries) {
      const search = await (await fetch('/api/search?q=' + encodeURIComponent(item.query))).json();
      const track = search.tracks[0];
      const events = [];
      for (const name of ['loadstart', 'loadedmetadata', 'canplay', 'play', 'playing', 'waiting', 'stalled', 'progress', 'timeupdate', 'error', 'ended']) audio.addEventListener(name, () => events.push({ name, time: audio.currentTime, error: audio.error && { code: audio.error.code, message: audio.error.message } }));
      audio.pause();
      audio.src = '/api/media/' + encodeURIComponent(track.id);
      audio.load();
      const firstPlaying = waitFor('playing');
      await audio.play();
      await firstPlaying;
      const startedAt = audio.currentTime;
      await wait(30000);
      const after30s = audio.currentTime;
      audio.pause();
      const paused = audio.paused;
      await wait(500);
      const resumedPlaying = waitFor('playing');
      await audio.play();
      await resumedPlaying;
      const resumed = !audio.paused;
      const seekTo = Math.min(10, Math.max(1, audio.duration - 1));
      audio.currentTime = seekTo;
      await wait(1500);
      const seeked = Math.abs(audio.currentTime - seekTo) < 2;
      results.push({ name: item.name, id: track.id, title: track.title, artist: track.artist.name, playing: events.some((event) => event.name === 'playing'), startedAt, after30s, continuous30s: after30s - startedAt >= 25, paused, resumed, seekTo, seeked, duration: audio.duration, fatalError: audio.error && { code: audio.error.code, message: audio.error.message }, eventNames: [...new Set(events.map((event) => event.name))] });
    }
    return results;
  })()`
});
console.log(JSON.stringify(result.result?.value || result.exceptionDetails, null, 2));
socket.close();
