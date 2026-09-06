import { Innertube, Platform } from 'youtubei.js/web';
Platform.shim.eval = async (data) => new Function(data.output)();
const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36';
const yt = await Innertube.create({ client_type: 'MWEB', generate_session_locally: true });
const info = await yt.getBasicInfo('J7p4bzqLvCw', { client: 'MWEB' });
const formats = [...(info.streaming_data?.adaptive_formats || []), ...(info.streaming_data?.formats || [])].filter((f) => [599, 600].includes(f.itag));
for (const format of formats) {
  const url = await format.decipher(yt.session.player);
  const length = Number(format.content_length || new URL(url).searchParams.get('clen'));
  const responses = [];
  for (const range of [`bytes=0-${length - 1}`, `bytes=0-`, `bytes=262144-${length - 1}`]) {
    const response = await fetch(url, { headers: { Range: range, Accept: '*/*', 'User-Agent': ua, Referer: 'https://m.youtube.com/' }, signal: AbortSignal.timeout(20000) });
    const reader = response.body?.getReader();
    const first = reader ? await reader.read() : { value: null };
    await reader?.cancel();
    responses.push({ range, status: response.status, contentRange: response.headers.get('content-range'), contentLength: response.headers.get('content-length'), bytes: first.value?.byteLength || 0 });
  }
  console.log(JSON.stringify({ itag: format.itag, mime: format.mime_type, bitrate: format.bitrate, length, responses }, null, 2));
}
