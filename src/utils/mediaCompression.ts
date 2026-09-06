/**
 * Media Compression Utilities
 * Handles image compression, resizing, and optimization for P2P transfer
 */

export interface CompressionOptions {
  maxWidth?: number;
  maxHeight?: number;
  quality?: number;
  maxSizeKB?: number;
  outputFormat?: 'jpeg' | 'png' | 'webp';
}

export interface CompressionResult {
  dataUrl: string;
  blob: Blob;
  width: number;
  height: number;
  sizeKB: number;
  originalSizeKB: number;
  compressionRatio: number;
}

/**
 * Compress an image file to target size
 */
export async function compressImage(
  file: File | Blob,
  options: CompressionOptions = {}
): Promise<CompressionResult> {
  const {
    maxWidth = 1920,
    maxHeight = 1920,
    quality = 0.8,
    maxSizeKB = 500,
    outputFormat = 'jpeg',
  } = options;

  return new Promise((resolve, reject) => {
    const reader = new FileReader();

    reader.onload = async (e) => {
      try {
        const img = new Image();
        
        img.onload = async () => {
          // Calculate new dimensions while maintaining aspect ratio
          let { width, height } = img;
          
          if (width > maxWidth || height > maxHeight) {
            const aspectRatio = width / height;
            
            if (width > height) {
              width = Math.min(width, maxWidth);
              height = width / aspectRatio;
            } else {
              height = Math.min(height, maxHeight);
              width = height * aspectRatio;
            }
          }

          // Create canvas and draw resized image
          const canvas = document.createElement('canvas');
          canvas.width = width;
          canvas.height = height;
          
          const ctx = canvas.getContext('2d');
          if (!ctx) {
            reject(new Error('Failed to get canvas context'));
            return;
          }

          // Use high-quality image smoothing
          ctx.imageSmoothingEnabled = true;
          ctx.imageSmoothingQuality = 'high';
          
          ctx.drawImage(img, 0, 0, width, height);

          // Try compression with decreasing quality until target size is met
          let currentQuality = quality;
          let blob: Blob | null = null;
          let attempts = 0;
          const maxAttempts = 5;

          while (attempts < maxAttempts) {
            blob = await new Promise<Blob | null>((res) => {
              canvas.toBlob(
                (b) => res(b),
                `image/${outputFormat}`,
                currentQuality
              );
            });

            if (!blob) {
              reject(new Error('Failed to create blob'));
              return;
            }

            const sizeKB = blob.size / 1024;
            
            // If size is acceptable, break
            if (sizeKB <= maxSizeKB || currentQuality <= 0.3) {
              break;
            }

            // Reduce quality for next attempt
            currentQuality *= 0.8;
            attempts++;
          }

          if (!blob) {
            reject(new Error('Failed to compress image'));
            return;
          }

          // Convert to data URL
          const dataUrl = canvas.toDataURL(`image/${outputFormat}`, currentQuality);
          
          const originalSize = file.size / 1024;
          const finalSize = blob.size / 1024;

          resolve({
            dataUrl,
            blob,
            width,
            height,
            sizeKB: finalSize,
            originalSizeKB: originalSize,
            compressionRatio: originalSize / finalSize,
          });
        };

        img.onerror = () => reject(new Error('Failed to load image'));
        img.src = e.target?.result as string;
      } catch (error) {
        reject(error);
      }
    };

    reader.onerror = () => reject(new Error('Failed to read file'));
    reader.readAsDataURL(file);
  });
}

/**
 * Generate a thumbnail from an image
 */
export async function generateThumbnail(
  dataUrl: string,
  maxSize: number = 100
): Promise<string> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    
    img.onload = () => {
      const canvas = document.createElement('canvas');
      const aspectRatio = img.width / img.height;
      
      let width = maxSize;
      let height = maxSize;
      
      if (aspectRatio > 1) {
        height = maxSize / aspectRatio;
      } else {
        width = maxSize * aspectRatio;
      }
      
      canvas.width = width;
      canvas.height = height;
      
      const ctx = canvas.getContext('2d');
      if (!ctx) {
        reject(new Error('Failed to get canvas context'));
        return;
      }
      
      ctx.drawImage(img, 0, 0, width, height);
      resolve(canvas.toDataURL('image/jpeg', 0.7));
    };
    
    img.onerror = () => reject(new Error('Failed to load image'));
    img.src = dataUrl;
  });
}

/**
 * Convert a data URL to Blob
 */
export function dataURLtoBlob(dataUrl: string): Blob {
  const arr = dataUrl.split(',');
  const mimeMatch = arr[0].match(/:(.*?);/);
  const mime = mimeMatch ? mimeMatch[1] : 'image/jpeg';
  const bstr = atob(arr[1]);
  let n = bstr.length;
  const u8arr = new Uint8Array(n);
  
  while (n--) {
    u8arr[n] = bstr.charCodeAt(n);
  }
  
  return new Blob([u8arr], { type: mime });
}

/**
 * Get image dimensions from data URL
 */
export async function getImageDimensions(
  dataUrl: string
): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    
    img.onload = () => {
      resolve({ width: img.width, height: img.height });
    };
    
    img.onerror = () => reject(new Error('Failed to load image'));
    img.src = dataUrl;
  });
}

/**
 * Validate image file type
 */
export function isValidImageType(file: File): boolean {
  const validTypes = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'image/gif'];
  return validTypes.includes(file.type);
}

/**
 * Format file size for display
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 Bytes';
  
  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  
  return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i];
}

