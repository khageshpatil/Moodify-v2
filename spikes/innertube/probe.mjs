import { Innertube, Platform } from 'youtubei.js/web';

Platform.shim.eval = async (data) => new Function(data.output)();

const tracks = [
  { name: 'mainstream English', query: 'The Weeknd Blinding Lights' },
  { name: 'Hindi Bollywood', query: 'Arijit Singh Tum Hi Ho' },
  { name: 'older song', query: 'Queen Bohemian Rhapsody' },
  { name: 'obscure song', query: 'Anuv Jain Alag Aasmaan' },
  { name: 'unresolvable track', query: 'this track does not exist zzz 918273645' },
];

const withTimeout = async (promise, ms) => {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`timeout after ${ms}ms`)), ms); }),
    ]);
  } finally {
    clearTimeout(timer);
  }
};

const firstSong = (search) => {
  const contents = search.songs?.contents || [];
  return contents.find((item) => item.id && item.title);
};

const normalize = (item) => ({
  id: item.id,
  title: item.title?.toString?.() || String(item.title || ''),
  artists: item.artists?.map?.((artist) => artist.name?.toString?.() || String(artist.name || '')) || [],
  album: item.album?.name?.toString?.() || item.album?.name || null,
  duration: item.duration?.seconds ?? item.duration_seconds ?? null,
});

const validateSource = async (url) => {
  const started = performance.now();
  const response = await withTimeout(fetch(url, {
    headers: { Range: 'bytes=0-1023' },
    signal: AbortSignal.timeout(10000),
  }), 12000);
  const reader = response.body?.getReader();
  const firstChunk = reader ? await reader.read() : { value: null };
  await reader?.cancel();
  return {
    ok: response.ok && Boolean(firstChunk.value?.byteLength),
    status: response.status,
    contentType: response.headers.get('content-type'),
    bytes: firstChunk.value?.byteLength || 0,
    latencyMs: Math.round(performance.now() - started),
  };
};

const run = async () => {
  const yt = await withTimeout(Innertube.create({ generate_session_locally: true }), 20000);
  const results = [];
  let firstResolvedUrl = null;

  for (const test of tracks) {
    const started = performance.now();
    const result = { ...test, search: null, resolution: null, source: null, error: null };
    try {
      const searchStarted = performance.now();
      const search = await withTimeout(yt.music.search(test.query, { type: 'song' }), 15000);
      const item = firstSong(search);
      result.search = { latencyMs: Math.round(performance.now() - searchStarted), found: Boolean(item), track: item ? normalize(item) : null };
      if (!item) throw new Error('no song result');

      const resolveStarted = performance.now();
      const info = await withTimeout(yt.music.getInfo(item.id), 20000);
      const format = info.chooseFormat({ type: 'audio', quality: 'best' });
      const sourceUrl = await withTimeout(format.decipher(yt.session.player), 20000);
      result.resolution = {
        latencyMs: Math.round(performance.now() - resolveStarted),
        formatId: format.itag || format.format_id || null,
        mimeType: format.mime_type || format.mimeType || null,
        codec: format.codecs || null,
        bitrate: format.bitrate || format.average_bitrate || null,
        expiresAt: Number(new URL(sourceUrl).searchParams.get('expire')) * 1000 || null,
        durationMs: format.approx_duration_ms || null,
        hasUrl: Boolean(sourceUrl),
      };
      if (!sourceUrl) throw new Error('resolver returned no URL');
      firstResolvedUrl ||= sourceUrl;
      result.source = await validateSource(sourceUrl);
    } catch (error) {
      result.error = { type: error?.constructor?.name || 'Error', message: error?.message || String(error) };
    }
    result.totalLatencyMs = Math.round(performance.now() - started);
    results.push(result);
    console.log(JSON.stringify(result, null, 2));
  }

  const repeatedStarted = performance.now();
  await withTimeout(yt.music.search('The Weeknd Blinding Lights', { type: 'song' }), 15000);
  const repeatedFirstMs = Math.round(performance.now() - repeatedStarted);
  const rapidStarted = performance.now();
  const rapid = await Promise.allSettled([
    yt.music.search('Arijit Singh Tum Hi Ho', { type: 'song' }),
    yt.music.search('Queen Bohemian Rhapsody', { type: 'song' }),
    yt.music.search('Anuv Jain Alag Aasmaan', { type: 'song' }),
  ]);

  const failureTests = {};
  try {
    await withTimeout(yt.music.getInfo('not-a-real-video-id'), 10000);
    failureTests.invalidTrack = { ok: false, error: 'unexpectedly resolved' };
  } catch (error) {
    failureTests.invalidTrack = { ok: true, type: error.constructor.name, message: error.message };
  }
  try {
    await fetch('http://127.0.0.1:9/network-failure');
    failureTests.networkFailure = { ok: false, error: 'unexpectedly connected' };
  } catch (error) {
    failureTests.networkFailure = { ok: true, type: error.constructor.name, message: error.message };
  }
  if (firstResolvedUrl) {
    const expiredUrl = new URL(firstResolvedUrl);
    expiredUrl.searchParams.set('expire', '1');
    failureTests.expiredSource = await validateSource(expiredUrl.toString());
  }
  try {
    const info = await withTimeout(yt.music.getInfo('J7p4bzqLvCw'), 20000);
    const upNext = await withTimeout(info.getUpNext(), 20000);
    failureTests.nextTrack = { ok: Boolean(upNext?.contents?.length), count: upNext?.contents?.length || 0 };
  } catch (error) {
    failureTests.nextTrack = { ok: false, type: error.constructor.name, message: error.message };
  }

  console.log(JSON.stringify({
    repeatedSearchMs: repeatedFirstMs,
    rapidSearchMs: Math.round(performance.now() - rapidStarted),
    rapidSearchStatuses: rapid.map((entry) => entry.status),
    failureTests,
    generatedAt: new Date().toISOString(),
  }, null, 2));
};

run().catch((error) => {
  console.error(JSON.stringify({ fatal: true, type: error?.constructor?.name, message: error?.message }, null, 2));
  process.exitCode = 1;
});
