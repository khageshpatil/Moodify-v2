import { Innertube, Platform } from 'youtubei.js/web';

Platform.shim.eval = async (data) => new Function(data.output)();
const id = process.env.TRACK_ID || 'J7p4bzqLvCw';
const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36';
const clients = ['YTMUSIC', 'WEB', 'MWEB', 'IOS', 'ANDROID', 'TV'];
const out = { id, clients: [], rangeExperiments: [] };

for (const client of clients) {
  const started = performance.now();
  try {
    const yt = await Innertube.create({ client_type: client, generate_session_locally: true });
    const info = client === 'YTMUSIC' ? await yt.music.getInfo(id) : await yt.getBasicInfo(id, { client });
    const formats = [...(info.streaming_data?.adaptive_formats || []), ...(info.streaming_data?.formats || [])]
      .filter((format) => format.has_audio && !format.has_video)
      .map((format) => ({
        itag: format.itag,
        mime: format.mime_type,
        bitrate: format.bitrate,
        averageBitrate: format.average_bitrate,
        codec: format.codecs,
        sampleRate: format.audio_sample_rate,
        channels: format.audio_channels,
        contentLength: format.content_length,
        hasUrl: Boolean(format.url),
        hasCipher: Boolean(format.cipher || format.signature_cipher),
      }));
    out.clients.push({ client, ms: Math.round(performance.now() - started), playability: info.playability_status?.status, formatCount: formats.length, formats });
  } catch (error) {
    out.clients.push({ client, ms: Math.round(performance.now() - started), error: { type: error.constructor.name, message: error.message } });
  }
}

const yt = await Innertube.create({ generate_session_locally: true });
const info = await yt.music.getInfo(id);
const audioFormats = [...(info.streaming_data?.adaptive_formats || []), ...(info.streaming_data?.formats || [])].filter((format) => format.has_audio && !format.has_video);
for (const format of audioFormats) {
  try {
    const url = await format.decipher(yt.session.player);
    if (!url) continue;
    const baseHeaders = { 'User-Agent': ua, Referer: 'https://music.youtube.com/', Accept: '*/*' };
    const parsed = new URL(url);
    const contentLength = Number(format.content_length || parsed.searchParams.get('clen'));
    const tests = [
      { name: 'range-header-1mb', url, headers: { ...baseHeaders, Range: 'bytes=0-1048575' } },
      { name: 'range-header-next-1mb', url, headers: { ...baseHeaders, Range: 'bytes=1048576-2097151' } },
      { name: 'range-header-256kb-next', url, headers: { ...baseHeaders, Range: 'bytes=262144-524287' } },
      { name: 'query-range-next', url: `${url}&range=1048576-2097151`, headers: baseHeaders },
      { name: 'query-range-first', url: `${url}&range=0-1048575`, headers: baseHeaders },
      { name: 'no-range', url, headers: baseHeaders },
    ];
    const formatResult = { itag: format.itag, mime: format.mime_type, contentLength, tests: [] };
    for (const test of tests) {
      const started = performance.now();
      try {
        const response = await fetch(test.url, { headers: test.headers, redirect: 'manual', signal: AbortSignal.timeout(15000) });
        const body = response.body?.getReader();
        const first = body ? await body.read() : { value: null };
        await body?.cancel();
        formatResult.tests.push({ name: test.name, status: response.status, location: response.headers.get('location'), contentType: response.headers.get('content-type'), contentRange: response.headers.get('content-range'), contentLength: response.headers.get('content-length'), bytes: first.value?.byteLength || 0, ms: Math.round(performance.now() - started) });
      } catch (error) {
        formatResult.tests.push({ name: test.name, error: { type: error.constructor.name, message: error.message }, ms: Math.round(performance.now() - started) });
      }
    }
    out.rangeExperiments.push(formatResult);
  } catch (error) {
    out.rangeExperiments.push({ itag: format.itag, error: { type: error.constructor.name, message: error.message } });
  }
}

console.log(JSON.stringify(out, null, 2));
