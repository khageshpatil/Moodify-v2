import { useState, useRef, useEffect } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { 
  Camera, 
  X, 
  RotateCw, 
  Upload, 
  Zap,
  ZapOff,
  CheckCircle,
  AlertCircle 
} from 'lucide-react';
import { compressImage } from '@/utils/mediaCompression';
import { useToast } from '@/hooks/use-toast';

interface CameraCaptureProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCapture: (dataUrl: string, metadata: {
    width: number;
    height: number;
    size: number;
    thumbnail: string;
  }) => void;
}

export const CameraCapture = ({ open, onOpenChange, onCapture }: CameraCaptureProps) => {
  const { toast } = useToast();
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [capturedImage, setCapturedImage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [facingMode, setFacingMode] = useState<'user' | 'environment'>('user');
  const [flashEnabled, setFlashEnabled] = useState(false);
  const [hasFlash, setHasFlash] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Initialize camera
  useEffect(() => {
    if (!open) {
      // Cleanup when dialog closes
      if (stream) {
        stream.getTracks().forEach(track => track.stop());
        setStream(null);
      }
      setCapturedImage(null);
      setError(null);
      return;
    }

    startCamera();

    return () => {
      if (stream) {
        stream.getTracks().forEach(track => track.stop());
      }
    };
  }, [open, facingMode]);

  const startCamera = async () => {
    try {
      setIsLoading(true);
      setError(null);

      const constraints: MediaStreamConstraints = {
        video: {
          facingMode,
          width: { ideal: 1920 },
          height: { ideal: 1080 },
        },
      };

      const mediaStream = await navigator.mediaDevices.getUserMedia(constraints);
      setStream(mediaStream);

      if (videoRef.current) {
        videoRef.current.srcObject = mediaStream;
      }

      // Check for flash support
      const videoTrack = mediaStream.getVideoTracks()[0];
      const capabilities = videoTrack.getCapabilities?.() as any;
      setHasFlash(capabilities?.torch === true);

    } catch (err) {
      console.error('Camera error:', err);
      setError(
        err instanceof Error && err.name === 'NotAllowedError'
          ? 'Camera permission denied. Please allow camera access.'
          : 'Failed to access camera. You can upload a photo instead.'
      );
    } finally {
      setIsLoading(false);
    }
  };

  const toggleFlash = async () => {
    if (!stream || !hasFlash) return;

    try {
      const videoTrack = stream.getVideoTracks()[0];
      await videoTrack.applyConstraints({
        // @ts-ignore - torch is not in standard types yet
        advanced: [{ torch: !flashEnabled }],
      });
      setFlashEnabled(!flashEnabled);
    } catch (err) {
      console.error('Flash toggle error:', err);
    }
  };

  const capturePhoto = () => {
    if (!videoRef.current || !canvasRef.current) return;

    const video = videoRef.current;
    const canvas = canvasRef.current;
    const context = canvas.getContext('2d');

    if (!context) return;

    // Set canvas size to match video
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;

    // Draw current video frame to canvas
    context.drawImage(video, 0, 0, canvas.width, canvas.height);

    // Get data URL
    const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
    setCapturedImage(dataUrl);
  };

  const retake = () => {
    setCapturedImage(null);
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (!file.type.startsWith('image/')) {
      toast({
        title: 'Invalid File',
        description: 'Please select an image file',
        variant: 'destructive',
      });
      return;
    }

    setIsLoading(true);
    try {
      const compressed = await compressImage(file, {
        maxWidth: 1920,
        maxHeight: 1920,
        quality: 0.8,
        maxSizeKB: 500,
      });

      setCapturedImage(compressed.dataUrl);
    } catch (err) {
      console.error('Upload error:', err);
      toast({
        title: 'Upload Failed',
        description: 'Could not process image',
        variant: 'destructive',
      });
    } finally {
      setIsLoading(false);
    }
  };

  const confirmCapture = async () => {
    if (!capturedImage) return;

    setIsLoading(true);
    try {
      // Compress the captured image
      const blob = await fetch(capturedImage).then(r => r.blob());
      const compressed = await compressImage(blob, {
        maxWidth: 1920,
        maxHeight: 1920,
        quality: 0.8,
        maxSizeKB: 500,
      });

      // Generate thumbnail
      const thumbnailCanvas = document.createElement('canvas');
      const img = new Image();
      
      await new Promise((resolve, reject) => {
        img.onload = resolve;
        img.onerror = reject;
        img.src = compressed.dataUrl;
      });

      const thumbnailSize = 100;
      const aspectRatio = img.width / img.height;
      let thumbWidth = thumbnailSize;
      let thumbHeight = thumbnailSize;
      
      if (aspectRatio > 1) {
        thumbHeight = thumbnailSize / aspectRatio;
      } else {
        thumbWidth = thumbnailSize * aspectRatio;
      }

      thumbnailCanvas.width = thumbWidth;
      thumbnailCanvas.height = thumbHeight;
      const ctx = thumbnailCanvas.getContext('2d');
      
      if (ctx) {
        ctx.drawImage(img, 0, 0, thumbWidth, thumbHeight);
      }

      const thumbnail = thumbnailCanvas.toDataURL('image/jpeg', 0.7);

      onCapture(compressed.dataUrl, {
        width: compressed.width,
        height: compressed.height,
        size: compressed.sizeKB * 1024,
        thumbnail,
      });

      // Close dialog
      onOpenChange(false);

      toast({
        title: '📸 Photo Captured!',
        description: `Compressed to ${Math.round(compressed.sizeKB)}KB`,
      });
    } catch (err) {
      console.error('Capture error:', err);
      toast({
        title: 'Capture Failed',
        description: 'Could not process photo',
        variant: 'destructive',
      });
    } finally {
      setIsLoading(false);
    }
  };

  const toggleCamera = () => {
    setFacingMode(prev => prev === 'user' ? 'environment' : 'user');
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[90vw] md:max-w-[600px] p-0 overflow-hidden">
        <DialogHeader className="p-4 pb-2">
          <DialogTitle className="flex items-center gap-2">
            <Camera className="w-5 h-5" />
            {capturedImage ? 'Preview Photo' : 'Capture Photo'}
          </DialogTitle>
        </DialogHeader>

        <div className="relative bg-black aspect-video flex items-center justify-center">
          {error ? (
            <div className="p-6 text-center">
              <Alert variant="destructive">
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>{error}</AlertDescription>
              </Alert>
              <Button
                onClick={() => fileInputRef.current?.click()}
                className="mt-4"
                variant="outline"
              >
                <Upload className="w-4 h-4 mr-2" />
                Upload Photo Instead
              </Button>
            </div>
          ) : capturedImage ? (
            <img
              src={capturedImage}
              alt="Captured"
              className="w-full h-full object-contain"
            />
          ) : (
            <>
              <video
                ref={videoRef}
                autoPlay
                playsInline
                muted
                className="w-full h-full object-cover"
              />
              <canvas ref={canvasRef} className="hidden" />
              
              {isLoading && (
                <div className="absolute inset-0 flex items-center justify-center bg-black/50">
                  <div className="text-white">Initializing camera...</div>
                </div>
              )}
            </>
          )}
        </div>

        <div className="p-4 flex flex-col gap-3">
          {!error && !capturedImage && (
            <div className="flex justify-between items-center">
              <Button
                size="sm"
                variant="outline"
                onClick={() => fileInputRef.current?.click()}
                disabled={isLoading}
              >
                <Upload className="w-4 h-4 mr-2" />
                Upload
              </Button>

              <Button
                size="lg"
                onClick={capturePhoto}
                disabled={isLoading || !stream}
                className="h-16 w-16 rounded-full"
              >
                <Camera className="w-6 h-6" />
              </Button>

              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={toggleCamera}
                  disabled={isLoading}
                >
                  <RotateCw className="w-4 h-4" />
                </Button>
                
                {hasFlash && (
                  <Button
                    size="sm"
                    variant={flashEnabled ? "default" : "outline"}
                    onClick={toggleFlash}
                    disabled={isLoading}
                  >
                    {flashEnabled ? (
                      <Zap className="w-4 h-4" />
                    ) : (
                      <ZapOff className="w-4 h-4" />
                    )}
                  </Button>
                )}
              </div>
            </div>
          )}

          {capturedImage && (
            <div className="flex gap-2">
              <Button
                variant="outline"
                onClick={retake}
                disabled={isLoading}
                className="flex-1"
              >
                <X className="w-4 h-4 mr-2" />
                Retake
              </Button>
              <Button
                onClick={confirmCapture}
                disabled={isLoading}
                className="flex-1"
              >
                <CheckCircle className="w-4 h-4 mr-2" />
                {isLoading ? 'Processing...' : 'Use Photo'}
              </Button>
            </div>
          )}
        </div>

        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          onChange={handleFileUpload}
          className="hidden"
        />
      </DialogContent>
    </Dialog>
  );
};

