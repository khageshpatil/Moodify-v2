import { Innertube, Platform } from 'youtubei.js/web';
Platform.shim.eval = async (data) => new Function(data.output)();
const clients = ['YTMUSIC_ANDROID', 'ANDROID_VR', 'TV_SIMPLY', 'TV_EMBEDDED', 'WEB_EMBEDDED', 'VISIONOS'];
for (const client of clients) {
  try {
    const yt = await Innertube.create({ client_type: client, generate_session_locally: true });
    const info = await yt.getBasicInfo('J7p4bzqLvCw', { client });
    const formats = (info.streaming_data?.adaptive_formats || []).filter((format) => format.has_audio && !format.has_video).map((format) => ({ itag: format.itag, mime: format.mime_type, bitrate: format.bitrate, length: format.content_length }));
    console.log(JSON.stringify({ client, status: info.playability_status?.status, formats, streamingKeys: Object.keys(info.streaming_data || {}) }));
  } catch (error) {
    console.log(JSON.stringify({ client, error: `${error.constructor.name}: ${error.message}` }));
  }
}
