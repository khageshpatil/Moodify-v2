// Utility for downloading music tracks
import { Track } from '@/data/mockMusic';

interface DownloadOptions {
  onProgress?: (progress: number) => void;
  onSuccess?: () => void;
  onError?: (error: Error) => void;
}

export const downloadTrack = async (
  track: Track,
  options?: DownloadOptions
): Promise<void> => {
  try {
    if (!track) {
      throw new Error('No track provided for download');
    }
    
    if (!track.url) {
      throw new Error('No download URL available for this track');
    }

    const { onProgress, onSuccess, onError } = options || {};

    // Fetch the audio file
    const response = await fetch(track.url);
    
    if (!response.ok) {
      throw new Error(`Download failed: ${response.statusText}`);
    }

    const contentLength = response.headers.get('content-length');
    const total = contentLength ? parseInt(contentLength, 10) : 0;

    // Read the stream for progress tracking
    const reader = response.body?.getReader();
    const chunks: Uint8Array[] = [];
    let receivedLength = 0;

    if (reader) {
      while (true) {
        const { done, value } = await reader.read();

        if (done) break;

        chunks.push(value);
        receivedLength += value.length;

        // Report progress
        if (total > 0 && onProgress) {
          const progress = (receivedLength / total) * 100;
          onProgress(Math.round(progress));
        }
      }
    }

    // Combine chunks into a single Uint8Array
    const chunksAll = new Uint8Array(receivedLength);
    let position = 0;
    for (const chunk of chunks) {
      chunksAll.set(chunk, position);
      position += chunk.length;
    }

    // Create blob from the audio data
    const blob = new Blob([chunksAll], { type: 'audio/mpeg' });

    // Create download link
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    
    // Sanitize filename with fallback values
    const sanitizedTitle = (track.title || 'unknown_track').replace(/[^a-z0-9]/gi, '_').toLowerCase();
    const sanitizedArtist = (track.artist || 'unknown_artist').replace(/[^a-z0-9]/gi, '_').toLowerCase();
    a.download = `${sanitizedArtist}-${sanitizedTitle}.mp3`;
    
    // Trigger download
    document.body.appendChild(a);
    a.click();
    
    // Cleanup
    document.body.removeChild(a);
    window.URL.revokeObjectURL(url);

    if (onSuccess) {
      onSuccess();
    }
  } catch (error) {
    console.error('Download error:', error);
    if (options?.onError) {
      options.onError(error instanceof Error ? error : new Error('Download failed'));
    }
  }
};

export const canDownloadTrack = (track: Track | null): boolean => {
  return !!(track?.url && track.url.startsWith('http'));
};

export const getDownloadSize = async (track: Track): Promise<string | null> => {
  try {
    if (!track.url) return null;

    const response = await fetch(track.url, { method: 'HEAD' });
    const contentLength = response.headers.get('content-length');

    if (!contentLength) return null;

    const bytes = parseInt(contentLength, 10);
    const megabytes = (bytes / (1024 * 1024)).toFixed(2);

    return `${megabytes} MB`;
  } catch {
    return null;
  }
};
