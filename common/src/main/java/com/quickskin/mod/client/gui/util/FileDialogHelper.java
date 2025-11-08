package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Helper class for opening native file dialogs
 * Uses TinyFileDialogs for cross-platform file selection
 */
@Environment(EnvType.CLIENT)
public class FileDialogHelper {

    /**
     * Opens a file dialog to select a PNG image (skin)
     * @param title Dialog title
     * @param onFileSelected Callback when file is selected (null if cancelled)
     */
    public static void openSkinFileDialog(String title, Consumer<Path> onFileSelected) {
        CompletableFuture.runAsync(() -> {
            try (MemoryStack stack = stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png")).flip();

                String file = TinyFileDialogs.tinyfd_openFileDialog(
                    title,
                    "",
                    filters,
                    "PNG Images",
                    false
                );

                if (file != null && !file.isEmpty()) {
                    Path filePath = Path.of(file);
                    onFileSelected.accept(filePath);
                } else {
                    QuickSkin.LOGGER.debug("File dialog cancelled");
                }
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Error opening file dialog", e);
            }
        });
    }

    /**
     * Opens a file dialog to select PNG or GIF images (capes)
     * @param title Dialog title
     * @param onFileSelected Callback when file is selected (null if cancelled)
     */
    public static void openCapeFileDialog(String title, Consumer<Path> onFileSelected) {
        CompletableFuture.runAsync(() -> {
            try (MemoryStack stack = stackPush()) {
                PointerBuffer filters = stack.mallocPointer(2);
                filters.put(stack.UTF8("*.png"));
                filters.put(stack.UTF8("*.gif"));
                filters.flip();

                String file = TinyFileDialogs.tinyfd_openFileDialog(
                    title,
                    "",
                    filters,
                    "PNG/GIF Images",
                    false
                );

                if (file != null && !file.isEmpty()) {
                    Path filePath = Path.of(file);
                    QuickSkin.LOGGER.info("File selected: {}", filePath);
                    onFileSelected.accept(filePath);
                } else {
                    QuickSkin.LOGGER.debug("File dialog cancelled");
                }
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Error opening file dialog", e);
            }
        });
    }

    /**
     * Opens a file dialog to select multiple PNG images
     * @param title Dialog title
     * @param onFilesSelected Callback when files are selected
     */
    public static void openMultipleFileDialog(String title, Consumer<Path[]> onFilesSelected) {
        CompletableFuture.runAsync(() -> {
            try (MemoryStack stack = stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png")).flip();

                String files = TinyFileDialogs.tinyfd_openFileDialog(
                    title,
                    "",
                    filters,
                    "PNG Images",
                    true // Allow multiple selection
                );

                if (files != null && !files.isEmpty()) {
                    // TinyFileDialogs returns multiple files separated by |
                    String[] filePaths = files.split("\\|");
                    Path[] paths = new Path[filePaths.length];
                    for (int i = 0; i < filePaths.length; i++) {
                        paths[i] = Path.of(filePaths[i]);
                    }
                    QuickSkin.LOGGER.info("Selected {} files", paths.length);
                    onFilesSelected.accept(paths);
                } else {
                    QuickSkin.LOGGER.debug("File dialog cancelled");
                }
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Error opening file dialog", e);
            }
        });
    }
}
