import { Innertube, Platform } from 'youtubei.js/web';
Platform.shim.eval = async (data) => new Function(data.output)();
const id = 'J7p4bzqLvCw';
const clients = ['YTMUSIC', 'WEB', 'MWEB', 'IOS', 'ANDROID', 'TV'];
const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36';
const ranges = ['bytes=0-262143', 'bytes=1048576-1310719'];
const request = async (url, range) => {
  const response = await fetch(url, { headers: { Range: range, Accept: '*/*', 'User-Agent': ua, Referer: 'https://music.youtube.com/' }, signal: AbortSignal.timeout(15000) });
  const reader = response.body?.getReader();
  const chunk = reader ? await reader.read() : { value: null };
  await reader?.cancel();
  return { range, status: response.status, contentRange: response.headers.get('content-range'), bytes: chunk.value?.byteLength || 0 };
};
const output = [];
for (const client of clients) {
  try {
    const yt = await Innertube.create({ client_type: client, generate_session_locally: true });
    const info = client === 'YTMUSIC' ? await yt.music.getInfo(id) : await yt.getBasicInfo(id, { client });
    const formats = [...(info.streaming_data?.adaptive_formats || []), ...(info.streaming_data?.formats || [])].filter((format) => format.has_audio && !format.has_video);
    for (const format of formats) {
      const url = await format.decipher(yt.session.player);
      output.push({ client, itag: format.itag, mime: format.mime_type, bitrate: format.bitrate, codec: format.codecs, sampleRate: format.audio_sample_rate, contentLength: format.content_length, ranges: url ? await Promise.all(ranges.map((range) => request(url, range))) : [], url: Boolean(url) });
    }
  } catch (error) {
    output.push({ client, error: `${error.constructor.name}: ${error.message}` });
  }
}
console.log(JSON.stringify(output, null, 2));
