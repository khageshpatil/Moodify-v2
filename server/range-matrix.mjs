import { Innertube, Platform } from 'youtubei.js/web';
Platform.shim.eval = async (data) => new Function(data.output)();
const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36';
const id = 'J7p4bzqLvCw';
const ranges = ['bytes=0-262143', 'bytes=262144-524287', 'bytes=524288-786431', 'bytes=786432-1048575', 'bytes=1048576-1310719', 'bytes=1310720-1572863'];
const getSource = async () => {
  const yt = await Innertube.create({ generate_session_locally: true });
  const info = await yt.music.getInfo(id);
  const format = info.chooseFormat({ type: 'audio', quality: 'best' });
  return { url: await format.decipher(yt.session.player), format };
};
const request = async (url, range, keepAlive = true) => {
  const response = await fetch(url, { headers: { Range: range, 'User-Agent': ua, Referer: 'https://music.youtube.com/', Accept: '*/*', Connection: keepAlive ? 'keep-alive' : 'close' }, signal: AbortSignal.timeout(15000) });
  const bytes = response.body ? await response.arrayBuffer() : new ArrayBuffer(0);
  return { range, status: response.status, contentRange: response.headers.get('content-range'), contentLength: response.headers.get('content-length'), bytes: bytes.byteLength };
};
const result = { sameSource: [], freshSource: [], newConnections: [] };
const same = await getSource();
for (const range of ranges) result.sameSource.push(await request(same.url, range));
for (const range of ranges) { const fresh = await getSource(); result.freshSource.push(await request(fresh.url, range)); }
for (const range of ranges) { const fresh = await getSource(); result.newConnections.push(await request(fresh.url, range, false)); }
console.log(JSON.stringify(result, null, 2));
