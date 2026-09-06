# 🎵 Song Download Feature

## Overview
The song download feature allows users to download tracks directly to their device. The feature includes progress tracking, error handling, and a beautiful UI that integrates seamlessly with Moodify's design system.

## Features

### ✨ Main Capabilities
- **Direct Downloads**: Download songs to local device storage
- **Progress Tracking**: Real-time download progress with percentage indicator
- **Smart File Naming**: Automatically sanitizes and formats filenames
- **Error Handling**: Graceful error handling with user-friendly messages
- **Availability Check**: Validates track download URLs before attempting download
- **Toast Notifications**: Success and error messages for user feedback
- **Multiple Integration Points**: Works in player controls and track listings

## Implementation

### Core Utilities
Located in [`src/utils/downloadMusic.ts`](./src/utils/downloadMusic.ts)

#### `downloadTrack(track, options)`
Main download function that handles the entire download process.

```typescript
await downloadTrack(track, {
  onProgress: (progress) => {
    console.log(`Download progress: ${progress}%`);
  },
  onSuccess: () => {
    console.log('Download complete!');
  },
  onError: (error) => {
    console.error('Download failed:', error);
  },
});
```

#### `canDownloadTrack(track)`
Validates if a track can be downloaded.

```typescript
if (canDownloadTrack(track)) {
  // Proceed with download
}
```

#### `getDownloadSize(track)`
Fetches the file size of a track (optional utility).

```typescript
const size = await getDownloadSize(track);
console.log(`File size: ${size}`); // "4.23 MB"
```

### UI Components

#### 1. **DownloadButton Component**
Reusable download button with built-in state management.

Located in [`src/components/DownloadButton.tsx`](./src/components/DownloadButton.tsx)

```typescript
import { DownloadButton } from '@/components/DownloadButton';

// Basic usage
<DownloadButton track={track} />

// With custom styling
<DownloadButton 
  track={track}
  variant="outline"
  size="sm"
  showLabel={true}
  className="text-primary"
/>
```

**Props:**
- `track` (required): The track to download
- `variant`: Button variant ('default' | 'ghost' | 'outline')
- `size`: Button size ('default' | 'sm' | 'lg' | 'icon')
- `showLabel`: Show download text label
- `className`: Additional CSS classes

**Features:**
- Tooltip showing download status
- Progress indicator while downloading
- Checkmark icon when complete
- Disabled state when unavailable

#### 2. **Music Player Integration**
Download option in the EnhancedMusicPlayer dropdown menu.

Located in [`src/components/EnhancedMusicPlayer.tsx`](./src/components/EnhancedMusicPlayer.tsx)

Available in:
- **Compact view**: More options (⋯) menu → "Download Song"
- **Expanded view**: More options (⋯) menu → "Download Song"

Shows progress percentage during download and success notification on completion.

#### 3. **Track Listings Integration**
Download buttons appear on hover in track listings.

Example in [`src/components/DiscoverView.tsx`](./src/components/DiscoverView.tsx)

```typescript
<div className="flex gap-2">
  <Button onClick={() => playTrack(track)}>
    <Play />
  </Button>
  <Button onClick={() => addToFavorites(track)}>
    <Heart />
  </Button>
  <DownloadButton track={track} variant="ghost" size="icon" />
</div>
```

## How It Works

### Download Flow
1. **Validation**: Check if track has a valid download URL
2. **Fetch**: Stream the audio file from the server
3. **Progress**: Track download progress and update UI
4. **Blob Creation**: Convert downloaded chunks to a Blob
5. **File Save**: Trigger browser download with sanitized filename
6. **Cleanup**: Remove temporary URLs and reset state
7. **Notification**: Show success or error toast

### File Naming Convention
```
artist_name-song_title.mp3
```
Example: `imagine_dragons-radioactive.mp3`

### Progress Tracking
```typescript
onProgress: (progress) => {
  // progress: 0-100
  setDownloadProgress(progress);
}
```

## Usage Examples

### Example 1: Standalone Download Function
```typescript
import { downloadTrack, canDownloadTrack } from '@/utils/downloadMusic';
import { useToast } from '@/hooks/use-toast';

const handleDownload = async () => {
  if (!canDownloadTrack(currentTrack)) {
    toast({
      title: 'Unavailable',
      description: 'This track cannot be downloaded',
      variant: 'destructive',
    });
    return;
  }

  await downloadTrack(currentTrack, {
    onProgress: (progress) => {
      console.log(`${progress}% complete`);
    },
    onSuccess: () => {
      toast({
        title: 'Success!',
        description: 'Song downloaded successfully',
      });
    },
    onError: (error) => {
      toast({
        title: 'Error',
        description: error.message,
        variant: 'destructive',
      });
    },
  });
};
```

### Example 2: Using DownloadButton
```typescript
import { DownloadButton } from '@/components/DownloadButton';

// In your component
<DownloadButton 
  track={currentTrack}
  variant="outline"
  size="default"
  showLabel={true}
/>
```

### Example 3: Custom Download UI
```typescript
import { useState } from 'react';
import { downloadTrack } from '@/utils/downloadMusic';
import { Download } from 'lucide-react';

const [downloading, setDownloading] = useState(false);
const [progress, setProgress] = useState(0);

const handleCustomDownload = async () => {
  setDownloading(true);
  
  await downloadTrack(track, {
    onProgress: setProgress,
    onSuccess: () => {
      setDownloading(false);
      setProgress(0);
    },
    onError: () => {
      setDownloading(false);
      setProgress(0);
    },
  });
};

return (
  <button onClick={handleCustomDownload} disabled={downloading}>
    <Download />
    {downloading ? `${progress}%` : 'Download'}
  </button>
);
```

## Browser Compatibility

The download feature uses:
- **Fetch API**: Supported in all modern browsers
- **Blob API**: Widely supported
- **URL.createObjectURL**: Standard across browsers
- **Download Attribute**: HTML5 feature (may prompt on iOS Safari)

### Known Limitations
- iOS Safari may open downloaded files instead of saving
- Some browsers may require user interaction to trigger download
- CORS restrictions apply to external URLs

## Error Handling

### Common Errors
1. **No URL Available**
   - Error: "No download URL available for this track"
   - Fix: Ensure track has a valid `url` property

2. **Network Error**
   - Error: "Download failed: [status]"
   - Fix: Check network connection and URL validity

3. **CORS Error**
   - Error: Failed to fetch
   - Fix: Ensure server has proper CORS headers

### Error Messages
All errors are shown via toast notifications with:
- Clear error title
- Descriptive error message
- Destructive variant styling (red)

## Performance Considerations

### Optimizations
- **Chunked Reading**: Streams data in chunks to prevent memory issues
- **Progress Throttling**: Updates UI efficiently during download
- **URL Cleanup**: Properly revokes object URLs to prevent memory leaks
- **Abort Support**: Can be extended to support download cancellation

### File Size Handling
- Large files (>50MB) download with progress tracking
- Small files download instantly
- No file size limitations enforced

## Testing

### Manual Testing Checklist
- [ ] Download from music player works
- [ ] Download from track listing works
- [ ] Progress indicator updates correctly
- [ ] Success toast appears on completion
- [ ] Error toast appears on failure
- [ ] Downloaded file has correct name
- [ ] Downloaded file plays correctly
- [ ] Button disables during download
- [ ] Tooltip shows correct status
- [ ] Works on mobile devices

## Future Enhancements

### Potential Features
- [ ] Download queue for multiple songs
- [ ] Batch download for playlists
- [ ] Download quality selection (320kbps, 128kbps, etc.)
- [ ] Download history tracking
- [ ] Resume interrupted downloads
- [ ] Download to specific folder selection
- [ ] Offline mode integration
- [ ] Download size preview before starting
- [ ] Cancel/pause download functionality

## Security & Legal

### Important Notes
⚠️ **Copyright Compliance**: Ensure you have the rights to download and distribute music files. This feature should only be used with:
- Royalty-free music
- Creative Commons licensed tracks
- Music from services with download permissions
- User-owned content

### Best Practices
- Respect copyright laws
- Use only with authorized sources
- Implement rate limiting if needed
- Add terms of service for downloads
- Consider implementing download limits
- Add authentication for sensitive content

## Support

For issues or questions:
1. Check the implementation in the source files
2. Review error messages in console
3. Verify track has valid URL
4. Test with different tracks
5. Check browser console for errors

---

**Last Updated**: February 2026  
**Version**: 1.0.0  
**Author**: Moodify Team
