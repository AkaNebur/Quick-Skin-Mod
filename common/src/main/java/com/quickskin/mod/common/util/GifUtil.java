package com.quickskin.mod.common.util;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Utility for processing animated GIF capes
 * Converts GIF to PNG atlas with animation metadata
 */
public class GifUtil {

    /**
     * Result of GIF processing
     */
    public record GifProcessResult(
        byte[] atlasImageData,        // PNG atlas with vertically stacked frames
        AnimationMetadata metadata,   // Animation frame timing
        int frameWidth,               // Width of a single frame
        int frameHeight               // Height of a single frame
    ) {}

    /**
     * Process GIF file and extract frames
     * @param input GIF file input stream
     * @return Processing result with atlas and metadata
     */
    public static GifProcessResult processGif(InputStream input) throws IOException {
        ImageInputStream imageStream = ImageIO.createImageInputStream(input);
        if (imageStream == null) {
            throw new IOException("Cannot create image input stream");
        }

        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) {
            throw new IOException("No GIF reader found");
        }

        ImageReader reader = readers.next();
        reader.setInput(imageStream, false);

        int frameCount = reader.getNumImages(true);
        if (frameCount == 0) {
            throw new IOException("GIF has no frames");
        }

        QuickSkin.LOGGER.info("Processing GIF with {} frames", frameCount);

        // Read first frame to get dimensions
        BufferedImage firstFrame = reader.read(0);
        int frameWidth = firstFrame.getWidth();
        int frameHeight = firstFrame.getHeight();

        // Validate dimensions for cape
        if (frameWidth != 64 || frameHeight != 32) {
            QuickSkin.LOGGER.warn("GIF dimensions {}x{} don't match standard cape size (64x32), attempting to process anyway", frameWidth, frameHeight);
        }

        // Process all frames
        List<BufferedImage> frames = new ArrayList<>();
        List<AnimationMetadata.FrameData> frameDataList = new ArrayList<>();

        // Canvas for frame reconstruction (handles disposal methods)
        BufferedImage canvas = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
        BufferedImage previousFrame = null;

        for (int i = 0; i < frameCount; i++) {
            try {
                // Read frame
                BufferedImage frame = reader.read(i);

                // Get frame metadata
                IIOMetadata metadata = reader.getImageMetadata(i);
                int delay = extractDelay(metadata);
                String disposalMethod = extractDisposalMethod(metadata);

                // Apply frame to canvas based on disposal method
                Graphics2D g = canvas.createGraphics();

                if (i == 0) {
                    // First frame: just draw it
                    g.drawImage(frame, 0, 0, null);
                } else {
                    // Handle disposal of previous frame
                    switch (disposalMethod) {
                        case "restoreToBackgroundColor":
                            // Clear canvas
                            g.setComposite(AlphaComposite.Clear);
                            g.fillRect(0, 0, frameWidth, frameHeight);
                            g.setComposite(AlphaComposite.SrcOver);
                            break;
                        case "restoreToPrevious":
                            // Restore to previous frame state
                            if (previousFrame != null) {
                                g.drawImage(previousFrame, 0, 0, null);
                            }
                            break;
                        case "doNotDispose":
                        case "none":
                        default:
                            // Keep current canvas content
                            break;
                    }

                    // Draw current frame
                    g.drawImage(frame, 0, 0, null);
                }

                g.dispose();

                // Save canvas state
                BufferedImage frameSnapshot = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D snapshotG = frameSnapshot.createGraphics();
                snapshotG.drawImage(canvas, 0, 0, null);
                snapshotG.dispose();

                frames.add(frameSnapshot);
                frameDataList.add(new AnimationMetadata.FrameData(delay, i));

                // Save for next iteration if needed
                if ("restoreToPrevious".equals(disposalMethod)) {
                    previousFrame = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D prevG = previousFrame.createGraphics();
                    prevG.drawImage(canvas, 0, 0, null);
                    prevG.dispose();
                }

            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to process frame {}", i, e);
            }
        }

        reader.dispose();
        imageStream.close();

        // Create vertical atlas (frames stacked top to bottom)
        int atlasHeight = frameHeight * frames.size();
        BufferedImage atlas = new BufferedImage(frameWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D atlasG = atlas.createGraphics();

        for (int i = 0; i < frames.size(); i++) {
            atlasG.drawImage(frames.get(i), 0, i * frameHeight, null);
        }
        atlasG.dispose();

        // Convert atlas to PNG bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(atlas, "PNG", baos);
        byte[] atlasData = baos.toByteArray();

        // Create metadata
        AnimationMetadata animationMetadata = new AnimationMetadata(frameDataList, frames.size());

        QuickSkin.LOGGER.info("GIF processed: {} frames, {}x{} per frame", frames.size(), frameWidth, frameHeight);

        return new GifProcessResult(atlasData, animationMetadata, frameWidth, frameHeight);
    }

    /**
     * Extract frame delay from GIF metadata
     * @return Delay in milliseconds (default 100ms if not found)
     */
    private static int extractDelay(IIOMetadata metadata) {
        try {
            String metadataFormat = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(metadataFormat);

            IIOMetadataNode graphicsControlExt = findNode(root, "GraphicControlExtension");
            if (graphicsControlExt != null) {
                String delayTimeStr = graphicsControlExt.getAttribute("delayTime");
                if (delayTimeStr != null && !delayTimeStr.isEmpty()) {
                    int delayTime = Integer.parseInt(delayTimeStr);
                    return delayTime * 10; // GIF delay is in 1/100th of a second
                }
            }
        } catch (Exception e) {
            QuickSkin.LOGGER.debug("Failed to extract delay from GIF metadata", e);
        }

        return 100; // Default 100ms
    }

    /**
     * Extract disposal method from GIF metadata
     */
    private static String extractDisposalMethod(IIOMetadata metadata) {
        try {
            String metadataFormat = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(metadataFormat);

            IIOMetadataNode graphicsControlExt = findNode(root, "GraphicControlExtension");
            if (graphicsControlExt != null) {
                String disposalMethod = graphicsControlExt.getAttribute("disposalMethod");
                if (disposalMethod != null && !disposalMethod.isEmpty()) {
                    return disposalMethod;
                }
            }
        } catch (Exception e) {
            QuickSkin.LOGGER.debug("Failed to extract disposal method from GIF metadata", e);
        }

        return "none";
    }

    /**
     * Find node by name in metadata tree
     */
    private static IIOMetadataNode findNode(IIOMetadataNode root, String nodeName) {
        if (root == null) {
            return null;
        }

        if (nodeName.equals(root.getNodeName())) {
            return root;
        }

        for (int i = 0; i < root.getLength(); i++) {
            IIOMetadataNode child = (IIOMetadataNode) root.item(i);
            IIOMetadataNode found = findNode(child, nodeName);
            if (found != null) {
                return found;
            }
        }

        return null;
    }
}
