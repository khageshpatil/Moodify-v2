import http from 'node:http';
import { Innertube, Platform } from 'youtubei.js/web';

Platform.shim.eval = async (data) => new Function(data.output)();
const yt = await Innertube.create({ generate_session_locally: true });
const port = Number(process.env.PORT || 8091);

const html = `<!doctype html>
<html><body>
<button id="play">Play resolved source</button>
<pre id="result">idle</pre>
<audio id="audio" controls></audio>
<script>
const result = document.querySelector('#result');
const audio = document.querySelector('#audio');
const params = new URLSearchParams(location.search);
const id = params.get('id');
const set = (event, extra = {}) => { result.textContent = JSON.stringify({ event, ...extra, currentTime: audio.currentTime, readyState: audio.readyState }, null, 2); };
['loadstart', 'loadedmetadata', 'canplay', 'playing', 'timeupdate', 'error', 'stalled', 'waiting', 'ended'].forEach((event) => audio.addEventListener(event, () => set(event, { mediaError: audio.error?.code || null })));
(async () => {
  if (!id) return set('missing-id');
  try {
    const response = await fetch('/resolve?id=' + encodeURIComponent(id));
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'resolve failed');
    audio.src = params.get('proxy') === '1' ? '/proxy?id=' + encodeURIComponent(id) : data.url;
    set('source-ready', { mimeType: data.mimeType, bitrate: data.bitrate });
  } catch (error) { set('resolve-failed', { message: error.message }); }
})();
document.querySelector('#play').addEventListener('click', async () => {
  try { await audio.play(); set('play-promise-resolved'); } catch (error) { set('play-rejected', { name: error.name, message: error.message }); }
});
</script></body></html>`;

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, `http://localhost:${port}`);
  if (url.pathname === '/resolve') {
    try {
      const info = await yt.music.getInfo(url.searchParams.get('id'));
      const format = info.chooseFormat({ type: 'audio', quality: 'best' });
      const source = await format.decipher(yt.session.player);
      response.writeHead(source ? 200 : 404, { 'content-type': 'application/json' });
      response.end(JSON.stringify(source ? { url: source, mimeType: format.mime_type, bitrate: format.bitrate } : { error: 'no source' }));
    } catch (error) {
      response.writeHead(502, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ error: `${error.constructor.name}: ${error.message}` }));
    }
    return;
  }
  if (url.pathname === '/proxy') {
    try {
      const info = await yt.music.getInfo(url.searchParams.get('id'));
      const format = info.chooseFormat({ type: 'audio', quality: 'best' });
      const source = await format.decipher(yt.session.player);
      const upstream = await fetch(source, {
        headers: request.headers.range ? { Range: request.headers.range } : { Range: 'bytes=0-' },
      });
      if (!upstream.ok || !upstream.body) throw new Error(`upstream ${upstream.status}`);
      response.writeHead(upstream.status, {
        'content-type': format.mime_type || 'audio/mp4',
        'cache-control': 'no-store',
        ...(upstream.headers.get('content-length') ? { 'content-length': upstream.headers.get('content-length') } : {}),
        ...(upstream.headers.get('content-range') ? { 'content-range': upstream.headers.get('content-range') } : {}),
        ...(upstream.headers.get('accept-ranges') ? { 'accept-ranges': upstream.headers.get('accept-ranges') } : {}),
      });
      for await (const chunk of upstream.body) response.write(chunk);
      response.end();
    } catch (error) {
      response.writeHead(502, { 'content-type': 'text/plain' });
      response.end(`${error.constructor.name}: ${error.message}`);
    }
    return;
  }
  response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
  response.end(html);
});

server.listen(port, '127.0.0.1', () => console.log(`SPIKE_BROWSER_URL=http://127.0.0.1:${port}`));
