import { Constants, Innertube, Platform } from 'youtubei.js/web';
import { SabrStream } from 'googlevideo/sabr-stream';
import { EnabledTrackTypes, buildSabrFormat, FormatKeyUtils } from 'googlevideo/utils';
import { getPoToken } from './provider/poToken.mjs';

Platform.shim.eval = async (data) => new Function(data.output)();

const id = process.env.TRACK_ID || 'J7p4bzqLvCw';
const yt = await Innertube.create({ generate_session_locally: true });
const info = await yt.music.getInfo(id);
const streaming = info.streaming_data;
const config = info.player_config?.media_common_config?.media_ustreamer_request_config?.video_playback_ustreamer_config;
const formats = (streaming?.adaptive_formats || []).map(buildSabrFormat);
const serverAbrStreamingUrl = await yt.session.player.decipher(streaming?.server_abr_streaming_url);
const poToken = await getPoToken(yt, id);
const startMs = Number(process.env.START_MS || 0);
const audioFormat = formats.find((format) => format.itag === 140) || formats.find((format) => format.mimeType?.includes('audio/mp4'));
const videoFormat = formats.find((format) => format.width) || formats[0];
const seekState = startMs > 0 ? {
  durationMs: Number(info.basic_info?.duration || 0) * 1000,
  requestNumber: 0,
  activeSabrContexts: [],
  sabrContextUpdates: [],
  formatToDiscard: FormatKeyUtils.fromFormat(videoFormat),
  cachedBufferedRanges: [],
  initializedFormats: [audioFormat, videoFormat].map((format) => ({
    formatKey: FormatKeyUtils.fromFormat(format),
    formatInitializationMetadata: {},
    downloadedSegments: [[1, { durationMs: String(startMs) }]],
    lastMediaHeaders: [],
  })),
  playerTimeMs: String(startMs),
} : undefined;

const sabrFetch = async (input, init) => {
  const response = await fetch(input, {
    ...init,
    headers: {
      ...(init?.headers || {}),
      'user-agent': yt.session.context.client.userAgent || 'com.google.android.youtube/21.03.36 (Linux; U; Android 16; en_US) gzip',
      'origin': 'https://music.youtube.com',
      'referer': 'https://music.youtube.com/',
    },
  });
  console.log(JSON.stringify({ event: 'sabr-http', method: init?.method || 'GET', url: new URL(input).pathname, queryKeys: [...new URL(input).searchParams.keys()].sort(), status: response.status, contentType: response.headers.get('content-type'), contentLength: response.headers.get('content-length') }));
  return response;
};

const stream = new SabrStream({
  serverAbrStreamingUrl,
  videoPlaybackUstreamerConfig: config,
  clientInfo: {
    clientName: parseInt(Constants.CLIENT_NAME_IDS[yt.session.context.client.clientName] || '67', 10),
    clientVersion: yt.session.context.client.clientVersion,
    osName: yt.session.context.client.osName,
    osVersion: yt.session.context.client.osVersion,
    screenWidthPoints: yt.session.context.client.screenWidthPoints,
    screenHeightPoints: yt.session.context.client.screenHeightPoints,
    screenPixelDensity: yt.session.context.client.screenPixelDensity,
    screenDensityFloat: yt.session.context.client.screenDensityFloat,
    utcOffsetMinutes: String(yt.session.context.client.utcOffsetMinutes),
    timeZone: yt.session.context.client.timeZone,
  },
  poToken,
  durationMs: Number(info.basic_info?.duration || 0) * 1000,
  formats,
  fetch: sabrFetch,
});

stream.on('formatInitialization', (event) => console.log(JSON.stringify({ event: 'formatInitialization', itag: event.formatInitializationMetadata?.formatId?.itag })));
stream.on('streamProtectionStatusUpdate', (event) => console.log(JSON.stringify({ event: 'streamProtectionStatusUpdate', status: event })));
stream.on('reloadPlayerResponse', (event) => console.log(JSON.stringify({ event: 'reloadPlayerResponse', value: event })));

try {
  const started = await stream.start({ audioFormat: () => audioFormat, videoFormat: () => videoFormat, state: seekState, enabledTrackTypes: EnabledTrackTypes.AUDIO_ONLY, maxRetries: 2, stallDetectionMs: 15000 });
  console.log(JSON.stringify({ event: 'started', selected: started.selectedFormats }));
  const reader = started.audioStream.getReader();
  let total = 0;
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const result = await reader.read();
    if (result.done) break;
    total += result.value.byteLength;
    console.log(JSON.stringify({ event: 'audio-chunk', bytes: result.value.byteLength, total }));
  }
  await reader.cancel();
  stream.abort();
  console.log(JSON.stringify({ event: 'finished', total }));
} catch (error) {
  console.error(JSON.stringify({ event: 'failed', type: error.constructor.name, message: error.message, stack: error.stack }));
  process.exitCode = 1;
}
