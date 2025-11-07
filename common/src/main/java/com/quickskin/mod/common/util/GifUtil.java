package com.quickskin.mod.common.util;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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

        // Get dimensions from first frame
        int frameWidth = reader.getWidth(0);
        int frameHeight = reader.getHeight(0);

        // Validate dimensions for cape
        if (frameWidth != 64 || frameHeight != 32) {
            QuickSkin.LOGGER.warn("GIF dimensions {}x{} don't match standard cape size (64x32), attempting to process anyway", frameWidth, frameHeight);
        }

        // Process all frames with proper reconstruction
        List<BufferedImage> reconstructedFrames = new ArrayList<>();
        List<AnimationMetadata.FrameData> frameDataList = new ArrayList<>();

        // Create master canvas for frame composition
        BufferedImage canvas = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D canvasGraphics = canvas.createGraphics();

        // Clear canvas to fully transparent to avoid black pixels in transparent areas
        canvasGraphics.setComposite(AlphaComposite.Clear);
        canvasGraphics.fillRect(0, 0, frameWidth, frameHeight);
        canvasGraphics.setComposite(AlphaComposite.SrcOver);

        int lastFrameX = 0;
        int lastFrameY = 0;
        int lastFrameWidth = 0;
        int lastFrameHeight = 0;

        for (int i = 0; i < frameCount; i++) {
            try {
                // Read the raw frame data (this is often just a "patch" of changed pixels)
                BufferedImage rawFrame = reader.read(i);

                // Get frame metadata
                IIOMetadata metadata = reader.getImageMetadata(i);
                Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());

                // Extract delay from GraphicControlExtension
                int delay = 100; // Default delay
                String disposalMethod = "none";
                Node gceNode = findNode(root, "GraphicControlExtension");
                if (gceNode != null) {
                    Node delayNode = gceNode.getAttributes().getNamedItem("delayTime");
                    if (delayNode != null) {
                        delay = Integer.parseInt(delayNode.getNodeValue()) * 10; // Convert to milliseconds
                        if (delay < 20) delay = 100; // Prevent absurdly fast animations
                    }
                    Node disposalNode = gceNode.getAttributes().getNamedItem("disposalMethod");
                    if (disposalNode != null) {
                        disposalMethod = disposalNode.getNodeValue();
                    }
                }

                // Extract frame position from ImageDescriptor
                int frameX = 0, frameY = 0;
                Node idNode = findNode(root, "ImageDescriptor");
                if (idNode != null) {
                    Node xNode = idNode.getAttributes().getNamedItem("imageLeftPosition");
                    Node yNode = idNode.getAttributes().getNamedItem("imageTopPosition");
                    if (xNode != null) frameX = Integer.parseInt(xNode.getNodeValue());
                    if (yNode != null) frameY = Integer.parseInt(yNode.getNodeValue());
                }

                // Draw the current frame's "patch" onto canvas at the correct offset
                canvasGraphics.drawImage(rawFrame, frameX, frameY, null);

                // Create a clean, independent copy of the fully reconstructed frame
                BufferedImage finalFrame = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D finalFrameGraphics = finalFrame.createGraphics();

                // Clear frame to fully transparent
                finalFrameGraphics.setComposite(AlphaComposite.Clear);
                finalFrameGraphics.fillRect(0, 0, frameWidth, frameHeight);
                finalFrameGraphics.setComposite(AlphaComposite.SrcOver);

                // Copy canvas to final frame
                finalFrameGraphics.drawImage(canvas, 0, 0, null);
                finalFrameGraphics.dispose();

                // Add the complete frame to our list
                reconstructedFrames.add(finalFrame);
                frameDataList.add(new AnimationMetadata.FrameData(delay, i));

                // Apply disposal method to prepare canvas for next frame
                if ("restoreToBackgroundColor".equalsIgnoreCase(disposalMethod)) {
                    // Clear the area where the frame was just drawn
                    canvasGraphics.setComposite(AlphaComposite.Clear);
                    canvasGraphics.fillRect(lastFrameX, lastFrameY, lastFrameWidth, lastFrameHeight);
                    canvasGraphics.setComposite(AlphaComposite.SrcOver);
                }
                // Note: RESTORE_TO_PREVIOUS is more complex and rare; this implementation handles common cases

                // Track frame bounds for next disposal
                lastFrameX = frameX;
                lastFrameY = frameY;
                lastFrameWidth = rawFrame.getWidth();
                lastFrameHeight = rawFrame.getHeight();

            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to process frame {}", i, e);
            }
        }

        canvasGraphics.dispose();
        reader.dispose();
        imageStream.close();

        // Create vertical atlas (frames stacked top to bottom)
        int atlasHeight = frameHeight * reconstructedFrames.size();
        BufferedImage atlas = new BufferedImage(frameWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D atlasG = atlas.createGraphics();

        // Clear atlas to fully transparent
        atlasG.setComposite(AlphaComposite.Clear);
        atlasG.fillRect(0, 0, frameWidth, atlasHeight);
        atlasG.setComposite(AlphaComposite.SrcOver);

        for (int i = 0; i < reconstructedFrames.size(); i++) {
            atlasG.drawImage(reconstructedFrames.get(i), 0, i * frameHeight, null);
        }
        atlasG.dispose();

        // Convert atlas to PNG bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(atlas, "PNG", baos);
        byte[] atlasData = baos.toByteArray();

        // Create metadata
        AnimationMetadata animationMetadata = new AnimationMetadata(frameDataList, reconstructedFrames.size());

        QuickSkin.LOGGER.info("GIF processed: {} frames, {}x{} per frame", reconstructedFrames.size(), frameWidth, frameHeight);

        return new GifProcessResult(atlasData, animationMetadata, frameWidth, frameHeight);
    }

    /**
     * Find node by name in metadata tree
     */
    private static Node findNode(Node rootNode, String nodeName) {
        if (rootNode == null) {
            return null;
        }

        if (nodeName.equalsIgnoreCase(rootNode.getNodeName())) {
            return rootNode;
        }

        NodeList children = rootNode.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            Node foundNode = findNode(node, nodeName);
            if (foundNode != null) {
                return foundNode;
            }
        }

        return null;
    }
}
