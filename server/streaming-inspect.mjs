import { Innertube, Platform } from 'youtubei.js/web';
Platform.shim.eval = async (data) => new Function(data.output)();
const clients = ['YTMUSIC', 'MWEB', 'WEB', 'IOS', 'ANDROID'];
for (const client of clients) {
  try {
    const yt = await Innertube.create({ client_type: client, generate_session_locally: true });
    const info = client === 'YTMUSIC' ? await yt.music.getInfo('J7p4bzqLvCw') : await yt.getBasicInfo('J7p4bzqLvCw', { client });
    console.log(JSON.stringify({ client, streamingKeys: Object.keys(info.streaming_data || {}), playability: info.playability_status, playerConfig: info.player_config, basic: info.basic_info }, null, 2).slice(0, 12000));
  } catch (error) { console.log(JSON.stringify({ client, error: `${error.constructor.name}: ${error.message}` })); }
}
