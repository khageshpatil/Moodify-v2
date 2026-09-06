import { useState, useEffect, useRef, useCallback } from 'react';
import { getCanonicalPlaybackEngine } from '@/playback/PlaybackEngine';

// Audio Effects Types
export interface AudioEffects {
  equalizer: {
    enabled: boolean;
    presets: 'flat' | 'rock' | 'pop' | 'jazz' | 'classical' | 'electronic' | 'custom';
    bands: number[]; // 10-band EQ: 32Hz, 64Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz
  };
  bassBoost: {
    enabled: boolean;
    level: number; // 0-100
  };
  virtualSurround: {
    enabled: boolean;
    level: number; // 0-100
  };
  reverb: {
    enabled: boolean;
    type: 'hall' | 'room' | 'cathedral' | 'plate' | 'spring';
    wetness: number; // 0-100
  };
  compressor: {
    enabled: boolean;
    threshold: number;
    ratio: number;
    attack: number;
    release: number;
  };
}

export interface PlaybackSettings {
  crossfadeDuration: number; // seconds
  gaplessPlayback: boolean;
  replayGain: boolean;
  playbackSpeed: number; // 0.5x to 2.0x
  normalizeVolume: boolean;
  fadeInOut: boolean;
}

interface AudioEngineState {
  audioContext: AudioContext | null;
  currentAudio: HTMLAudioElement | null;
  nextAudio: HTMLAudioElement | null;
  gainNode: GainNode | null;
  equalizerNodes: BiquadFilterNode[];
  bassBoostNode: BiquadFilterNode | null;
  reverbNode: ConvolverNode | null;
  compressorNode: DynamicsCompressorNode | null;
  analyzerNode: AnalyserNode | null;
  effects: AudioEffects;
  settings: PlaybackSettings;
  isTransitioning: boolean;
  visualizerData: Uint8Array;
}

type SharedAudioGraph = Pick<AudioEngineState, 'audioContext' | 'currentAudio' | 'gainNode' | 'equalizerNodes' | 'bassBoostNode' | 'reverbNode' | 'compressorNode' | 'analyzerNode'>;
let sharedAudioGraph: SharedAudioGraph | null = null;

const DEFAULT_EFFECTS: AudioEffects = {
  equalizer: {
    enabled: false,
    presets: 'flat',
    bands: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], // All bands at 0dB
  },
  bassBoost: {
    enabled: false,
    level: 0,
  },
  virtualSurround: {
    enabled: false,
    level: 0,
  },
  reverb: {
    enabled: false,
    type: 'hall',
    wetness: 20,
  },
  compressor: {
    enabled: false,
    threshold: -24,
    ratio: 12,
    attack: 0.003,
    release: 0.25,
  },
};

const DEFAULT_SETTINGS: PlaybackSettings = {
  crossfadeDuration: 3,
  gaplessPlayback: true,
  replayGain: true,
  playbackSpeed: 1.0,
  normalizeVolume: true,
  fadeInOut: true,
};

// EQ Presets
const EQ_PRESETS: Record<string, number[]> = {
  flat: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  rock: [5, 4, -1, -2, -1, 1, 3, 4, 4, 4],
  pop: [-1, 2, 4, 4, 1, -1, -2, -2, -1, -1],
  jazz: [4, 3, 1, 2, -1, -1, 0, 1, 2, 3],
  classical: [5, 4, 3, 2, -1, -1, 0, 2, 3, 4],
  electronic: [5, 4, 1, 0, -2, 2, 1, 2, 4, 5],
};

export const useEnhancedAudioEngine = () => {
  const [state, setState] = useState<AudioEngineState>({
    audioContext: null,
    currentAudio: null,
    nextAudio: null,
    gainNode: null,
    equalizerNodes: [],
    bassBoostNode: null,
    reverbNode: null,
    compressorNode: null,
    analyzerNode: null,
    effects: DEFAULT_EFFECTS,
    settings: DEFAULT_SETTINGS,
    isTransitioning: false,
    visualizerData: new Uint8Array(256),
  });

  const crossfadeTimeoutRef = useRef<NodeJS.Timeout>();
  const visualizerAnimationRef = useRef<number>();

  // Initialize Audio Context and Nodes
  const initializeAudioContext = useCallback(async () => {
    try {
      // EnhancedMusicPlayer and PremiumAudioControls can mount together. A
      // media element may only be passed to createMediaElementSource once,
      // so both consumers must share the same graph and AudioContext.
      if (sharedAudioGraph) {
        setState(prev => ({ ...prev, ...sharedAudioGraph }));
        return sharedAudioGraph.audioContext;
      }

      const audioContext = new (window.AudioContext || (window as any).webkitAudioContext)();
      
      // Create audio nodes
      const gainNode = audioContext.createGain();
      const analyzerNode = audioContext.createAnalyser();
      const compressorNode = audioContext.createDynamicsCompressor();
      const canonicalAudio = getCanonicalPlaybackEngine().getAudioElement();
      const mediaSource = audioContext.createMediaElementSource(canonicalAudio);
      mediaSource.connect(gainNode);
      
      // Create 10-band equalizer
      const equalizerNodes = Array.from({ length: 10 }, (_, i) => {
        const filter = audioContext.createBiquadFilter();
        const frequencies = [32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
        filter.type = i === 0 ? 'lowshelf' : i === 9 ? 'highshelf' : 'peaking';
        filter.frequency.value = frequencies[i];
        filter.Q.value = 1;
        filter.gain.value = 0;
        return filter;
      });

      // Create bass boost filter
      const bassBoostNode = audioContext.createBiquadFilter();
      bassBoostNode.type = 'lowshelf';
      bassBoostNode.frequency.value = 100;
      bassBoostNode.gain.value = 0;

      // Create reverb convolver
      const reverbNode = audioContext.createConvolver();
      
      // Setup analyzer for visualizer
      analyzerNode.fftSize = 512;
      analyzerNode.smoothingTimeConstant = 0.8;

      // Connect nodes in chain
      let previousNode = gainNode;
      
      // Connect equalizer chain
      equalizerNodes.forEach(node => {
        previousNode.connect(node);
        previousNode = node;
      });
      
      // Connect other effects
      previousNode.connect(bassBoostNode);
      previousNode = bassBoostNode;
      
      previousNode.connect(compressorNode);
      previousNode = compressorNode;
      
      previousNode.connect(analyzerNode);
      analyzerNode.connect(audioContext.destination);

      sharedAudioGraph = {
        audioContext,
        currentAudio: canonicalAudio,
        gainNode,
        equalizerNodes,
        bassBoostNode,
        reverbNode,
        compressorNode,
        analyzerNode,
      };

      setState(prev => ({
        ...prev,
        audioContext,
        gainNode,
        equalizerNodes,
        bassBoostNode,
        reverbNode,
        compressorNode,
        analyzerNode,
        currentAudio: canonicalAudio,
      }));

      return audioContext;
    } catch (error) {
      console.error('Failed to initialize audio context:', error);
      return null;
    }
  }, []);

  // Create reverb impulse response
  const createReverbImpulse = useCallback((type: string, wetness: number): AudioBuffer | null => {
    if (!state.audioContext) return null;

    const length = state.audioContext.sampleRate * 2; // 2 seconds
    const impulse = state.audioContext.createBuffer(2, length, state.audioContext.sampleRate);
    
    const left = impulse.getChannelData(0);
    const right = impulse.getChannelData(1);
    
    // Generate different reverb characteristics
    const decay = wetness / 100;
    
    for (let i = 0; i < length; i++) {
      const n = length - i;
      let sample = (Math.random() * 2 - 1) * Math.pow(n / length, decay);
      
      // Apply different reverb characteristics
      switch (type) {
        case 'hall':
          sample *= Math.sin(i * 0.01) * 0.5 + 0.5;
          break;
        case 'room':
          sample *= Math.exp(-i * 0.0001);
          break;
        case 'cathedral':
          sample *= Math.sin(i * 0.001) * 0.8 + 0.2;
          break;
        case 'plate':
          sample *= Math.sin(i * 0.1) * 0.3 + 0.7;
          break;
        case 'spring':
          sample *= Math.sin(i * 0.05) * 0.6 + 0.4;
          break;
      }
      
      left[i] = sample;
      right[i] = sample;
    }
    
    return impulse;
  }, [state.audioContext]);

  // Apply EQ preset
  const applyEQPreset = useCallback((preset: string) => {
    const bands = EQ_PRESETS[preset] || EQ_PRESETS.flat;
    
    setState(prev => ({
      ...prev,
      effects: {
        ...prev.effects,
        equalizer: {
          ...prev.effects.equalizer,
          presets: preset as any,
          bands,
        },
      },
    }));

    // Apply to audio nodes
    state.equalizerNodes.forEach((node, i) => {
      if (node) {
        node.gain.setValueAtTime(bands[i], state.audioContext?.currentTime || 0);
      }
    });
  }, [state.equalizerNodes, state.audioContext]);

  // Update bass boost
  const updateBassBoost = useCallback((level: number) => {
    setState(prev => ({
      ...prev,
      effects: {
        ...prev.effects,
        bassBoost: {
          ...prev.effects.bassBoost,
          level,
        },
      },
    }));

    if (state.bassBoostNode && state.audioContext) {
      state.bassBoostNode.gain.setValueAtTime(
        level * 0.2, // Scale to reasonable dB range
        state.audioContext.currentTime
      );
    }
  }, [state.bassBoostNode, state.audioContext]);

  // Crossfade between tracks
  const crossfadeToTrack = useCallback((newTrackUrl: string, duration: number = 3) => {
    if (!state.audioContext || !state.gainNode) return null;

    setState(prev => ({ ...prev, isTransitioning: true }));

    // Crossfade is intentionally deferred until the canonical engine exposes
    // a supported dual-source transition. Never create a second media element.
    void newTrackUrl;
    const newAudio = state.currentAudio;
    if (!newAudio) return null;
    
    // Crossfade logic
    const fadeStartTime = state.audioContext.currentTime;
    const fadeEndTime = fadeStartTime + duration;
    
    // Fade out current track
    if (state.currentAudio && state.gainNode) {
      state.gainNode.gain.setValueAtTime(1, fadeStartTime);
      state.gainNode.gain.linearRampToValueAtTime(0, fadeEndTime);
    }
    
    // The canonical engine remains the only playback owner.
    crossfadeTimeoutRef.current = setTimeout(() => {
      if (state.currentAudio) {
        state.currentAudio.pause();
        state.currentAudio.currentTime = 0;
      }
      
      setState(prev => ({
        ...prev,
        currentAudio: newAudio,
        nextAudio: null,
        isTransitioning: false,
      }));
    }, duration * 1000);
    
    return newAudio;
  }, [state.audioContext, state.gainNode, state.currentAudio, state.equalizerNodes]);

  // Update visualizer data
  const updateVisualizerData = useCallback(() => {
    if (!state.analyzerNode) return;

    const dataArray = new Uint8Array(state.analyzerNode.frequencyBinCount);
    state.analyzerNode.getByteFrequencyData(dataArray);
    
    setState(prev => ({
      ...prev,
      visualizerData: dataArray,
    }));

    visualizerAnimationRef.current = requestAnimationFrame(updateVisualizerData);
  }, [state.analyzerNode]);

  // Set playback speed
  const setPlaybackSpeed = useCallback((speed: number) => {
    setState(prev => ({
      ...prev,
      settings: {
        ...prev.settings,
        playbackSpeed: Math.max(0.5, Math.min(2.0, speed)),
      },
    }));

    if (state.currentAudio) {
      state.currentAudio.playbackRate = speed;
    }
  }, [state.currentAudio]);

  // Initialize on mount
  useEffect(() => {
    initializeAudioContext();
    
    return () => {
      if (crossfadeTimeoutRef.current) {
        clearTimeout(crossfadeTimeoutRef.current);
      }
      if (visualizerAnimationRef.current) {
        cancelAnimationFrame(visualizerAnimationRef.current);
      }
    };
  }, []);

  // Browsers suspend Web Audio contexts created outside a user gesture. The
  // canonical player still owns playback, but this graph must be resumed by
  // the same gesture that starts playback or the output remains silent.
  useEffect(() => {
    if (!state.audioContext) return undefined;
    const resumeAudioContext = () => {
      if (state.audioContext?.state === 'suspended') void state.audioContext.resume();
    };
    window.addEventListener('pointerdown', resumeAudioContext);
    window.addEventListener('keydown', resumeAudioContext);
    return () => {
      window.removeEventListener('pointerdown', resumeAudioContext);
      window.removeEventListener('keydown', resumeAudioContext);
    };
  }, [state.audioContext]);

  // Start visualizer when analyzer is ready
  useEffect(() => {
    if (state.analyzerNode && !visualizerAnimationRef.current) {
      updateVisualizerData();
    }
  }, [state.analyzerNode, updateVisualizerData]);

  return {
    // State
    audioContext: state.audioContext,
    currentAudio: state.currentAudio,
    effects: state.effects,
    settings: state.settings,
    isTransitioning: state.isTransitioning,
    visualizerData: state.visualizerData,
    
    // Actions
    initializeAudioContext,
    applyEQPreset,
    updateBassBoost,
    crossfadeToTrack,
    setPlaybackSpeed,
    
    // Audio nodes for direct manipulation
    gainNode: state.gainNode,
    equalizerNodes: state.equalizerNodes,
    bassBoostNode: state.bassBoostNode,
    analyzerNode: state.analyzerNode,
  };
};
