import { useState } from 'react';
import { Download, Loader2, CheckCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Track } from '@/data/mockMusic';
import { downloadTrack, canDownloadTrack } from '@/utils/downloadMusic';
import { useToast } from '@/hooks/use-toast';
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip';

interface DownloadButtonProps {
  track: Track;
  variant?: 'default' | 'ghost' | 'outline';
  size?: 'default' | 'sm' | 'lg' | 'icon';
  showLabel?: boolean;
  className?: string;
}

export const DownloadButton = ({ 
  track, 
  variant = 'ghost', 
  size = 'sm',
  showLabel = false,
  className = ''
}: DownloadButtonProps) => {
  const { toast } = useToast();
  const [isDownloading, setIsDownloading] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);
  const [isComplete, setIsComplete] = useState(false);

  const handleDownload = async (e: React.MouseEvent) => {
    e.stopPropagation(); // Prevent track selection when clicking download

    if (!canDownloadTrack(track)) {
      toast({
        title: 'Download Unavailable',
        description: 'This track cannot be downloaded',
        variant: 'destructive',
      });
      return;
    }

    setIsDownloading(true);
    setDownloadProgress(0);

    await downloadTrack(track, {
      onProgress: (progress) => {
        setDownloadProgress(progress);
      },
      onSuccess: () => {
        setIsComplete(true);
        
        // Reset complete state after 2 seconds
        setTimeout(() => {
          setIsComplete(false);
        }, 2000);
        
        setIsDownloading(false);
        setDownloadProgress(0);
      },
      onError: (error) => {
        toast({
          title: 'Download Failed',
          description: error.message,
          variant: 'destructive',
        });
        setIsDownloading(false);
        setDownloadProgress(0);
      },
    });
  };

  const isDisabled = isDownloading || !canDownloadTrack(track);

  const buttonContent = (
    <>
      {isComplete ? (
        <CheckCircle className="w-4 h-4 text-green-500" />
      ) : isDownloading ? (
        <Loader2 className="w-4 h-4 animate-spin" />
      ) : (
        <Download className="w-4 h-4" />
      )}
      {showLabel && (
        <span className="ml-2">
          {isComplete ? 'Downloaded' : isDownloading ? 'Downloading...' : 'Download'}
        </span>
      )}
    </>
  );

  const tooltipText = isComplete 
    ? 'Downloaded!' 
    : isDownloading 
    ? 'Downloading...' 
    : canDownloadTrack(track)
    ? 'Download song'
    : 'Download unavailable';

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant={variant}
            size={size}
            onClick={handleDownload}
            disabled={isDisabled}
            className={`transition-all ${isComplete ? 'text-green-500' : ''} ${className}`}
          >
            {buttonContent}
          </Button>
        </TooltipTrigger>
        <TooltipContent>
          <p>{tooltipText}</p>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
};
