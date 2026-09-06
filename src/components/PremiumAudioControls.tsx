import { useState } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Slider } from '@/components/ui/slider';
import { Switch } from '@/components/ui/switch';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { 
  Sliders, 
  Volume2, 
  Waves, 
  Music, 
  Zap, 
  Timer, 
  Gauge,
  Headphones,
  Settings,
  RotateCcw,
  Play
} from 'lucide-react';
import { useEnhancedAudioEngine } from '@/hooks/useEnhancedAudioEngine';

interface PremiumAudioControlsProps {
  isOpen: boolean;
  onClose: () => void;
}

export const PremiumAudioControls = ({ isOpen, onClose }: PremiumAudioControlsProps) => {
  const {
    effects,
    settings,
    applyEQPreset,
    updateBassBoost,
    setPlaybackSpeed,
    visualizerData,
  } = useEnhancedAudioEngine();

  const [sleepTimer, setSleepTimer] = useState(0);
  const [customEQBands, setCustomEQBands] = useState(effects.equalizer.bands);

  if (!isOpen) return null;

  const handleEQBandChange = (bandIndex: number, value: number) => {
    const newBands = [...customEQBands];
    newBands[bandIndex] = value;
    setCustomEQBands(newBands);
  };

  const eqFrequencies = ['32Hz', '64Hz', '125Hz', '250Hz', '500Hz', '1kHz', '2kHz', '4kHz', '8kHz', '16kHz'];

  return (
    <div className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm flex items-center justify-center p-4">
      <Card className="w-full max-w-4xl max-h-[90vh] overflow-hidden">
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle className="flex items-center gap-2">
            <Headphones className="w-5 h-5" />
            Premium Audio Controls
          </CardTitle>
          <Button variant="ghost" size="sm" onClick={onClose}>
            ✕
          </Button>
        </CardHeader>
        
        <CardContent className="overflow-y-auto">
          <Tabs defaultValue="equalizer" className="w-full">
            <TabsList className="grid w-full grid-cols-5">
              <TabsTrigger value="equalizer">
                <Sliders className="w-4 h-4 mr-2" />
                EQ
              </TabsTrigger>
              <TabsTrigger value="effects">
                <Waves className="w-4 h-4 mr-2" />
                Effects
              </TabsTrigger>
              <TabsTrigger value="playback">
                <Play className="w-4 h-4 mr-2" />
                Playback
              </TabsTrigger>
              <TabsTrigger value="visualizer">
                <Music className="w-4 h-4 mr-2" />
                Visualizer
              </TabsTrigger>
              <TabsTrigger value="advanced">
                <Settings className="w-4 h-4 mr-2" />
                Advanced
              </TabsTrigger>
            </TabsList>

            {/* Equalizer Tab */}
            <TabsContent value="equalizer" className="space-y-6">
              <div>
                <div className="flex items-center justify-between mb-4">
                  <h3 className="text-lg font-semibold">10-Band Equalizer</h3>
                  <div className="flex items-center gap-2">
                    <Switch 
                      checked={effects.equalizer.enabled}
                      onCheckedChange={(enabled) => {
                        // Update equalizer enabled state
                      }}
                    />
                    <span className="text-sm">Enabled</span>
                  </div>
                </div>

                {/* EQ Presets */}
                <div className="mb-6">
                  <label className="text-sm font-medium mb-2 block">Presets</label>
                  <div className="flex flex-wrap gap-2">
                    {['flat', 'rock', 'pop', 'jazz', 'classical', 'electronic'].map((preset) => (
                      <Button
                        key={preset}
                        variant={effects.equalizer.presets === preset ? 'default' : 'outline'}
                        size="sm"
                        onClick={() => applyEQPreset(preset)}
                        className="capitalize"
                      >
                        {preset}
                      </Button>
                    ))}
                  </div>
                </div>

                {/* EQ Sliders */}
                <div className="grid grid-cols-5 md:grid-cols-10 gap-4">
                  {eqFrequencies.map((freq, index) => (
                    <div key={freq} className="flex flex-col items-center space-y-2">
                      <div className="h-32 flex items-end">
                        <Slider
                          orientation="vertical"
                          value={[customEQBands[index]]}
                          onValueChange={([value]) => handleEQBandChange(index, value)}
                          max={12}
                          min={-12}
                          step={0.5}
                          className="h-full"
                        />
                      </div>
                      <span className="text-xs font-mono">{freq}</span>
                      <span className="text-xs text-muted-foreground">
                        {customEQBands[index] > 0 ? '+' : ''}{customEQBands[index]}dB
                      </span>
                    </div>
                  ))}
                </div>

                <div className="flex gap-2 mt-4">
                  <Button variant="outline" size="sm">
                    <RotateCcw className="w-4 h-4 mr-2" />
                    Reset
                  </Button>
                  <Button size="sm">
                    Save Custom
                  </Button>
                </div>
              </div>
            </TabsContent>

            {/* Effects Tab */}
            <TabsContent value="effects" className="space-y-6">
              {/* Bass Boost */}
              <Card>
                <CardHeader className="pb-3">
                  <div className="flex items-center justify-between">
                    <CardTitle className="text-base">Bass Boost</CardTitle>
                    <Switch 
                      checked={effects.bassBoost.enabled}
                      onCheckedChange={(enabled) => {
                        // Update bass boost enabled state
                      }}
                    />
                  </div>
                </CardHeader>
                <CardContent>
                  <div className="space-y-2">
                    <div className="flex justify-between text-sm">
                      <span>Level</span>
                      <span>{effects.bassBoost.level}%</span>
                    </div>
                    <Slider
                      value={[effects.bassBoost.level]}
                      onValueChange={([value]) => updateBassBoost(value)}
                      max={100}
                      step={1}
                      className="w-full"
                    />
                  </div>
                </CardContent>
              </Card>

              {/* Virtual Surround */}
              <Card>
                <CardHeader className="pb-3">
                  <div className="flex items-center justify-between">
                    <CardTitle className="text-base">Virtual Surround</CardTitle>
                    <Switch 
                      checked={effects.virtualSurround.enabled}
                      onCheckedChange={(enabled) => {
                        // Update virtual surround enabled state
                      }}
                    />
                  </div>
                </CardHeader>
                <CardContent>
                  <div className="space-y-2">
                    <div className="flex justify-between text-sm">
                      <span>Spaciousness</span>
                      <span>{effects.virtualSurround.level}%</span>
                    </div>
                    <Slider
                      value={[effects.virtualSurround.level]}
                      onValueChange={([value]) => {
                        // Update virtual surround level
                      }}
                      max={100}
                      step={1}
                      className="w-full"
                    />
                  </div>
                </CardContent>
              </Card>

              {/* Reverb */}
              <Card>
                <CardHeader className="pb-3">
                  <div className="flex items-center justify-between">
                    <CardTitle className="text-base">Reverb</CardTitle>
                    <Switch 
                      checked={effects.reverb.enabled}
                      onCheckedChange={(enabled) => {
                        // Update reverb enabled state
                      }}
                    />
                  </div>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div>
                    <label className="text-sm font-medium mb-2 block">Type</label>
                    <Select value={effects.reverb.type}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="hall">Concert Hall</SelectItem>
                        <SelectItem value="room">Room</SelectItem>
                        <SelectItem value="cathedral">Cathedral</SelectItem>
                        <SelectItem value="plate">Plate</SelectItem>
                        <SelectItem value="spring">Spring</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <div className="flex justify-between text-sm">
                      <span>Wetness</span>
                      <span>{effects.reverb.wetness}%</span>
                    </div>
                    <Slider
                      value={[effects.reverb.wetness]}
                      onValueChange={([value]) => {
                        // Update reverb wetness
                      }}
                      max={100}
                      step={1}
                      className="w-full"
                    />
                  </div>
                </CardContent>
              </Card>
            </TabsContent>

            {/* Playback Tab */}
            <TabsContent value="playback" className="space-y-6">
              {/* Playback Speed */}
              <Card>
                <CardHeader className="pb-3">
                  <CardTitle className="text-base flex items-center gap-2">
                    <Gauge className="w-4 h-4" />
                    Playback Speed
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="space-y-4">
                    <div className="flex justify-between text-sm">
                      <span>Speed</span>
                      <span>{settings.playbackSpeed}x</span>
                    </div>
                    <Slider
                      value={[settings.playbackSpeed]}
                      onValueChange={([value]) => setPlaybackSpeed(value)}
                      min={0.5}
                      max={2.0}
                      step={0.1}
                      className="w-full"
                    />
                    <div className="flex justify-between text-xs text-muted-foreground">
                      <span>0.5x</span>
                      <span>1.0x</span>
                      <span>2.0x</span>
                    </div>
                  </div>
                </CardContent>
              </Card>

              {/* Crossfade */}
              <Card>
                <CardHeader className="pb-3">
                  <CardTitle className="text-base">Crossfade</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="space-y-4">
                    <div className="flex justify-between text-sm">
                      <span>Duration</span>
                      <span>{settings.crossfadeDuration}s</span>
                    </div>
                    <Slider
                      value={[settings.crossfadeDuration]}
                      onValueChange={([value]) => {
                        // Update crossfade duration
                      }}
                      max={10}
                      min={0}
                      step={0.5}
                      className="w-full"
                    />
                  </div>
                </CardContent>
              </Card>

              {/* Sleep Timer */}
              <Card>
                <CardHeader className="pb-3">
                  <CardTitle className="text-base flex items-center gap-2">
                    <Timer className="w-4 h-4" />
                    Sleep Timer
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="space-y-4">
                    <div className="grid grid-cols-4 gap-2">
                      {[0, 15, 30, 60].map((minutes) => (
                        <Button
                          key={minutes}
                          variant={sleepTimer === minutes ? 'default' : 'outline'}
                          size="sm"
                          onClick={() => setSleepTimer(minutes)}
                        >
                          {minutes === 0 ? 'Off' : `${minutes}m`}
                        </Button>
                      ))}
                    </div>
                    {sleepTimer > 0 && (
                      <div className="text-sm text-muted-foreground text-center">
                        Music will stop in {sleepTimer} minutes
                      </div>
                    )}
                  </div>
                </CardContent>
              </Card>

              {/* Playback Settings */}
              <Card>
                <CardHeader className="pb-3">
                  <CardTitle className="text-base">Settings</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div className="flex items-center justify-between">
                    <span className="text-sm">Gapless Playback</span>
                    <Switch checked={settings.gaplessPlayback} />
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm">Normalize Volume</span>
                    <Switch checked={settings.normalizeVolume} />
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm">Fade In/Out</span>
                    <Switch checked={settings.fadeInOut} />
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm">Replay Gain</span>
                    <Switch checked={settings.replayGain} />
                  </div>
                </CardContent>
              </Card>
            </TabsContent>

            {/* Visualizer Tab */}
            <TabsContent value="visualizer" className="space-y-6">
              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Audio Spectrum</CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="h-32 bg-gradient-to-t from-primary/20 to-primary/5 rounded-lg p-4 flex items-end justify-center gap-1">
                    {Array.from({ length: 32 }, (_, i) => {
                      const height = visualizerData[i * 8] || 0;
                      return (
                        <div
                          key={i}
                          className="bg-gradient-to-t from-primary to-primary/60 rounded-sm transition-all duration-75"
                          style={{
                            width: '6px',
                            height: `${Math.max(2, (height / 255) * 100)}px`,
                          }}
                        />
                      );
                    })}
                  </div>
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Visualizer Settings</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div className="flex items-center justify-between">
                    <span className="text-sm">Show Visualizer</span>
                    <Switch />
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm">Full Screen Mode</span>
                    <Switch />
                  </div>
                  <div>
                    <label className="text-sm font-medium mb-2 block">Style</label>
                    <Select defaultValue="bars">
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="bars">Bars</SelectItem>
                        <SelectItem value="wave">Wave</SelectItem>
                        <SelectItem value="circular">Circular</SelectItem>
                        <SelectItem value="particles">Particles</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                </CardContent>
              </Card>
            </TabsContent>

            {/* Advanced Tab */}
            <TabsContent value="advanced" className="space-y-6">
              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Audio Quality</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div>
                    <label className="text-sm font-medium mb-2 block">Sample Rate</label>
                    <Select defaultValue="44100">
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="44100">44.1 kHz</SelectItem>
                        <SelectItem value="48000">48 kHz</SelectItem>
                        <SelectItem value="96000">96 kHz</SelectItem>
                        <SelectItem value="192000">192 kHz</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div>
                    <label className="text-sm font-medium mb-2 block">Bit Depth</label>
                    <Select defaultValue="16">
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="16">16-bit</SelectItem>
                        <SelectItem value="24">24-bit</SelectItem>
                        <SelectItem value="32">32-bit</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Performance</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div className="flex items-center justify-between">
                    <span className="text-sm">Hardware Acceleration</span>
                    <Switch defaultChecked />
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm">Low Latency Mode</span>
                    <Switch />
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm">Audio Buffer Size</span>
                    <Select defaultValue="512">
                      <SelectTrigger className="w-24">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="128">128</SelectItem>
                        <SelectItem value="256">256</SelectItem>
                        <SelectItem value="512">512</SelectItem>
                        <SelectItem value="1024">1024</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                </CardContent>
              </Card>

              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Reset Options</CardTitle>
                </CardHeader>
                <CardContent className="space-y-2">
                  <Button variant="outline" className="w-full">
                    Reset Audio Settings
                  </Button>
                  <Button variant="outline" className="w-full">
                    Reset All Effects
                  </Button>
                  <Button variant="destructive" className="w-full">
                    Factory Reset
                  </Button>
                </CardContent>
              </Card>
            </TabsContent>
          </Tabs>
        </CardContent>
      </Card>
    </div>
  );
};
