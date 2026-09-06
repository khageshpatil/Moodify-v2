import { Innertube, Platform } from 'youtubei.js/web';
Platform.shim.eval = async (data) => new Function(data.output)();
const clients = ['YTMUSIC', 'MWEB', 'IOS', 'ANDROID'];
const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36';
for (const client of clients) {
  try {
    const yt = await Innertube.create({ client_type: client, generate_session_locally: true });
    const info = client === 'YTMUSIC' ? await yt.music.getInfo('J7p4bzqLvCw') : await yt.getBasicInfo('J7p4bzqLvCw', { client });
    const result = { client, streaming: {} };
    for (const key of ['dash_manifest_url', 'hls_manifest_url', 'server_abr_streaming_url']) {
      const value = info.streaming_data?.[key];
      if (!value) continue;
      try {
        const r = await fetch(value, { headers: { 'User-Agent': ua, Referer: 'https://music.youtube.com/' }, signal: AbortSignal.timeout(20000) });
        const body = await r.text();
        result.streaming[key] = { status: r.status, contentType: r.headers.get('content-type'), length: body.length, sample: body.slice(0, 180) };
      } catch (error) { result.streaming[key] = { error: `${error.constructor.name}: ${error.message}` }; }
    }
    console.log(JSON.stringify(result, null, 2));
  } catch (error) { console.log(JSON.stringify({ client, error: `${error.constructor.name}: ${error.message}` })); }
}
